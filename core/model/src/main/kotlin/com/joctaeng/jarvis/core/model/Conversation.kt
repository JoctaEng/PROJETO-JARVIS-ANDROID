package com.joctaeng.jarvis.core.model

/** Quem produziu uma mensagem na conversa. */
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/** Uma mensagem de conversa, independente do provedor de IA. */
data class ChatMessage(
    val role: Role,
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    /** Arquivos anexados pelo usuário (imagem/áudio vão ao modelo que os lê; documentos já viram texto em [text]). */
    val attachments: List<Attachment> = emptyList(),
)

/** Tipo de anexo, pelo que o cérebro precisa para ler. */
enum class AttachmentKind { IMAGE, AUDIO, DOCUMENT }

/**
 * Arquivo que o usuário anexou pelo seletor do sistema. [path] é a cópia privada no aparelho (imagem e áudio vão
 * como arquivo ao modelo multimodal); [text] é o texto extraído localmente (PDF, DOCX, TXT, CSV).
 */
data class Attachment(
    val kind: AttachmentKind,
    val name: String,
    val mime: String,
    val path: String? = null,
    val text: String? = null,
)

/** O que um provedor de IA sabe fazer. */
enum class Capability { TEXT, VISION, AUDIO, TOOLS, STREAMING }

/** Onde o processamento acontece — usado pelo orquestrador e exibido ao usuário. */
enum class ProviderLocation { ON_DEVICE, OWN_SERVER, EXTERNAL_CLOUD }

/** Sensibilidade do conteúdo de um pedido (seção 7.2, regra 2). */
enum class Sensitivity { NORMAL, SENSITIVE }

/** Complexidade estimada de uma tarefa (seção 7.2, regras 4 e 5). */
enum class TaskComplexity { SIMPLE, COMPLEX }
