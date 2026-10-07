// Ponte JNI do Euno com o llama.cpp (ggml-org, MIT), compilada com llama.cpp v0.5.0. Adaptada do EduMath (mesmo autor).
//
// F1b (desempenho no celular):
//  - CACHE DE PREFIXO: o KV do ultimo prompt fica na memoria. A cada pedido calculamos o prefixo comum (em tokens) entre o
//    que ja esta no KV e o novo prompt; so o resto e processado (llama_memory_seq_rm + decode do restante). O prefixo
//    estatico (instrucoes + ficha do sistema) so e lido uma vez (ou no aquecimento ao abrir o Mathie).
//  - "thinking" do Qwen3 DESLIGADO: o template recebe o bloco <think></think> vazio logo apos "assistant" (equivale a
//    enable_thinking=false do template oficial) e a geracao nunca comeca com <think>.
//  - threads de geracao e de prefill separados e ajustaveis em tempo de execucao (llama_set_n_threads).
//  - metricas por pedido: tokens do prompt, tokens reaproveitados do cache, tempo de prefill, 1o token, tokens/s.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <string>
#include <vector>
#include <mutex>
#include <cstring>
#include <chrono>
#include "llama.h"

#define TAG "EunoLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {
using clk = std::chrono::steady_clock;
inline long long ms_desde(clk::time_point t0) { return std::chrono::duration_cast<std::chrono::milliseconds>(clk::now() - t0).count(); }

std::mutex g_mutex;
llama_model*   g_model = nullptr;
llama_context* g_ctx   = nullptr;
int            g_nctx  = 0;
int            g_nbatch = 512;
int            g_threads = 4;
int            g_threads_batch = 4;
bool           g_backend_ok = false;
std::vector<llama_token> g_kv_tokens;   // tokens cujo KV (sequencia 0, posicoes 0..n-1) esta na memoria

// metricas do ultimo pedido: [0]=tokens do prompt, [1]=reaproveitados do cache, [2]=prefill ms, [3]=1o token ms (desde o inicio),
// [4]=tokens gerados, [5]=geracao ms (do 1o token ao fim), [6]=tokenizacao+template ms
long long g_metricas[7] = {0, 0, 0, -1, 0, 0, 0};

void liberar_tudo() {
    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_kv_tokens.clear();
}

// Emite so sequencias UTF-8 completas (um token pode quebrar um caractere no meio; NewStringUTF falha com isso).
size_t utf8_completo(const std::string& s) {
    size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    int cont = 0;
    while (i > 0 && cont < 4 && (static_cast<unsigned char>(s[i - 1]) & 0xC0) == 0x80) { --i; ++cont; }
    if (i == 0) return n;
    unsigned char lead = static_cast<unsigned char>(s[i - 1]);
    int esperado = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
    if (esperado > 1 && cont + 1 < esperado) return i - 1;  // sequencia incompleta no fim
    return n;
}

// mensagens (roles/contents) -> texto pelo template de chat do proprio GGUF
bool aplicar_template(JNIEnv* env, jobjectArray jroles, jobjectArray jconts, bool add_ass, std::string& saida) {
    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(n), conts(n);
    for (jsize i = 0; i < n; ++i) {
        auto r = (jstring) env->GetObjectArrayElement(jroles, i);
        auto c = (jstring) env->GetObjectArrayElement(jconts, i);
        const char* rs = env->GetStringUTFChars(r, nullptr); roles[i] = rs; env->ReleaseStringUTFChars(r, rs);
        const char* cs = env->GetStringUTFChars(c, nullptr); conts[i] = cs; env->ReleaseStringUTFChars(c, cs);
        env->DeleteLocalRef(r); env->DeleteLocalRef(c);
    }
    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; ++i) { msgs[i].role = roles[i].c_str(); msgs[i].content = conts[i].c_str(); }
    const char* tmpl = llama_model_chat_template(g_model, nullptr);
    size_t total = 4096;
    for (jsize i = 0; i < n; ++i) total += conts[i].size() * 2 + roles[i].size() + 64;
    std::vector<char> buf(total);
    int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_ass, buf.data(), (int) buf.size());
    if (len > (int) buf.size()) { buf.resize(len + 1); len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_ass, buf.data(), (int) buf.size()); }
    if (len < 0) return false;
    saida.assign(buf.data(), len);
    return true;
}

bool tokenizar(const std::string& prompt, std::vector<llama_token>& toks) {
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    int ntok = -llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, true, true);
    if (ntok <= 0) return false;
    toks.resize(ntok);
    return llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), toks.data(), ntok, true, true) >= 0;
}

// Reaproveita o prefixo comum do KV e processa so o resto. Devolve false em erro. n_reuso = tokens aproveitados.
bool preencher(const std::vector<llama_token>& toks, int& n_reuso) {
    size_t comum = 0;
    const size_t lim = std::min(g_kv_tokens.size(), toks.size());
    while (comum < lim && g_kv_tokens[comum] == toks[comum]) ++comum;
    if (comum >= toks.size()) comum = toks.size() - 1;   // sempre decodifica ao menos 1 token (precisa dos logits)
    llama_memory_t mem = llama_get_memory(g_ctx);
    if (comum < g_kv_tokens.size()) {
        if (!llama_memory_seq_rm(mem, 0, (llama_pos) comum, -1)) {   // remocao parcial nao suportada: recomeca do zero
            llama_memory_clear(mem, true);
            comum = 0;
        }
        g_kv_tokens.resize(comum);
    }
    n_reuso = (int) comum;
    for (size_t i = comum; i < toks.size(); i += g_nbatch) {
        int c = (int) std::min<size_t>(g_nbatch, toks.size() - i);
        if (llama_decode(g_ctx, llama_batch_get_one(const_cast<llama_token*>(toks.data()) + i, c)) != 0) {
            llama_memory_clear(mem, true);
            g_kv_tokens.clear();
            return false;
        }
        g_kv_tokens.insert(g_kv_tokens.end(), toks.begin() + i, toks.begin() + i + c);
    }
    return true;
}
}  // namespace

extern "C" {

// codigos: 0 ok; -1 modelo; -2 contexto
JNIEXPORT jint JNICALL Java_com_joctaeng_jarvis_mind_llama_LlamaNative_nativeLoad(
        JNIEnv* env, jclass, jstring jpath, jint nctx, jint threads, jint threads_batch, jint n_batch) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_backend_ok) { llama_backend_init(); g_backend_ok = true; }
    liberar_tudo();
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;   // v0.5.0: use_mmap virou load_mode
    mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!g_model) { LOGE("falha ao carregar o modelo"); return -1; }
    llama_context_params cp = llama_context_default_params();
    g_nbatch = std::max(64, std::min((int) n_batch, 1024));
    cp.n_ctx = static_cast<uint32_t>(nctx);
    cp.n_batch = g_nbatch;
    cp.n_ubatch = g_nbatch;
    cp.n_threads = threads;
    cp.n_threads_batch = threads_batch > 0 ? threads_batch : threads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cp.no_perf = true;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { LOGE("falha ao criar o contexto"); liberar_tudo(); return -2; }
    g_nctx = nctx; g_threads = threads; g_threads_batch = cp.n_threads_batch;
    LOGI("modelo carregado, n_ctx=%d threads=%d threads_batch=%d n_batch=%d", nctx, g_threads, g_threads_batch, g_nbatch);
    return 0;
}

JNIEXPORT void JNICALL Java_com_joctaeng_jarvis_mind_llama_LlamaNative_nativeUnload(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_mutex);
    liberar_tudo();
}

JNIEXPORT jboolean JNICALL Java_com_joctaeng_jarvis_mind_llama_LlamaNative_nativeLoaded(JNIEnv*, jclass) {
    return g_model != nullptr && g_ctx != nullptr;
}

JNIEXPORT void JNICALL Java_com_joctaeng_jarvis_mind_llama_LlamaNative_nativeSetThreads(JNIEnv*, jclass, jint threads, jint threads_batch) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_ctx) return;
    g_threads = std::max(1, (int) threads);
    g_threads_batch = threads_batch > 0 ? (int) threads_batch : g_threads;
    llama_set_n_threads(g_ctx, g_threads, g_threads_batch);
}

JNIEXPORT void JNICALL Java_com_joctaeng_jarvis_mind_llama_LlamaNative_nativeResetCache(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), true);
    g_kv_tokens.clear();
}

// [0]=tokens do prompt, [1]=reaproveitados do cache, [2]=prefill ms, [3]=1o token ms, [4]=tokens gerados, [5]=geracao ms, [6]=template+tokenizacao ms,
// [7]=tokens atualmente no cache, [8]=threads, [9]=threads de prefill
JNIEXPORT jlongArray JNICALL Java_com_joctaeng_jarvis_mind_llama_LlamaNative_nativeMetrics(JNIEnv* env, jclass) {
    jlong v[10];
    for (int i = 0; i < 7; ++i) v[i] = g_metricas[i];
    v[7] = (jlong) g_kv_tokens.size(); v[8] = g_threads; v[9] = g_threads_batch;
    jlongArray a = env->NewLongArray(10);
    env->SetLongArrayRegion(a, 0, 10, v);
    return a;
}

JNIEXPORT jstring JNICALL Java_com_joctaeng_jarvis_mind_llama_LlamaNative_nativeSystemInfo(JNIEnv* env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

// Aquecimento: processa (e guarda no KV) o prefixo estatico (so mensagens de sistema), sem gerar nada. Devolve ms ou negativo.
JNIEXPORT jlong JNICALL Java_com_joctaeng_jarvis_mind_llama_LlamaNative_nativeWarmup(
        JNIEnv* env, jclass, jobjectArray jroles, jobjectArray jconts) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_model || !g_ctx) return -1;
    const auto t0 = clk::now();
    std::string prompt;
    if (!aplicar_template(env, jroles, jconts, false, prompt)) return -4;
    std::vector<llama_token> toks;
    if (!tokenizar(prompt, toks)) return -5;
    if ((int) toks.size() + 64 > g_nctx) return -6;
    int reuso = 0;
    llama_set_n_threads(g_ctx, g_threads, g_threads_batch);
    if (!preencher(toks, reuso)) return -7;
    return ms_desde(t0);
}

JNIEXPORT jint JNICALL Java_com_joctaeng_jarvis_mind_llama_LlamaNative_nativeGenerate(
        JNIEnv* env, jclass, jobjectArray jroles, jobjectArray jconts, jint max_tokens, jfloat temperatura,
        jboolean sem_pensar, jobject callback) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_model || !g_ctx) return -1;
    const auto t0 = clk::now();
    for (int i = 0; i < 7; ++i) g_metricas[i] = 0;
    g_metricas[3] = -1;

    // 1) mensagens -> prompt pelo template de chat do proprio GGUF (+ bloco de pensamento vazio no Qwen3)
    std::string prompt;
    if (!aplicar_template(env, jroles, jconts, true, prompt)) return -4;
    if (sem_pensar) prompt += "<think>\n\n</think>\n\n";   // Qwen3 sem raciocinio (enable_thinking=false)

    // 2) tokenizar
    std::vector<llama_token> toks;
    if (!tokenizar(prompt, toks)) return -5;
    const int ntok = (int) toks.size();
    int max_gen = max_tokens;
    if (ntok + 16 > g_nctx) return -6;                         // prompt nao cabe no contexto
    if (ntok + max_gen > g_nctx) max_gen = g_nctx - ntok;      // encurta a resposta em vez de falhar
    g_metricas[0] = ntok;
    g_metricas[6] = ms_desde(t0);

    // 3) amostrador
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperatura <= 0.01f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperatura));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // 4) prefill so do que nao esta no cache de prefixo
    const auto t1 = clk::now();
    int reuso = 0;
    llama_set_n_threads(g_ctx, g_threads, g_threads_batch);
    if (!preencher(toks, reuso)) { llama_sampler_free(smpl); return -7; }
    g_metricas[1] = reuso;
    g_metricas[2] = ms_desde(t1);

    // 5) geracao com streaming
    jclass cbCls = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)Z");
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    int fim = 1;  // 1 = limite de tokens
    std::string pendente;
    long long n_gerados = 0;
    clk::time_point t_primeiro = clk::now();
    bool viu_primeiro = false;
    for (int i = 0; i < max_gen; ++i) {
        llama_token t = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, t)) { fim = 0; break; }
        if (!viu_primeiro) { viu_primeiro = true; t_primeiro = clk::now(); g_metricas[3] = ms_desde(t0); }
        ++n_gerados;
        char piece[256];
        int pl = llama_token_to_piece(vocab, t, piece, sizeof(piece), 0, false);
        if (pl > 0) pendente.append(piece, pl);
        size_t ok = utf8_completo(pendente);
        if (ok > 0) {
            std::string saida = pendente.substr(0, ok);
            pendente.erase(0, ok);
            jstring js = env->NewStringUTF(saida.c_str());
            jboolean seguir = env->CallBooleanMethod(callback, onToken, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) { env->ExceptionClear(); fim = 2; break; }
            if (!seguir) { fim = 2; break; }
        }
        if (llama_decode(g_ctx, llama_batch_get_one(&t, 1)) != 0) { fim = -8; break; }
        g_kv_tokens.push_back(t);
    }
    g_metricas[4] = n_gerados;
    g_metricas[5] = viu_primeiro ? ms_desde(t_primeiro) : 0;
    llama_sampler_free(smpl);
    return fim;
}

}  // extern "C"
