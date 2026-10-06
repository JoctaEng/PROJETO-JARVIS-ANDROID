# Fase 0 — Como instalar e testar no celular

Objetivo da Fase 0 (roteiro, seção 12): eliminar os riscos técnicos **antes** de construir a interface final. Este APK não é o JARVIS; é uma bancada de testes com cinco provas de conceito (PoCs) que medem tudo no Redmi Note 13 Pro+ real.

## 1. Baixar e instalar o APK

1. No GitHub, abra **Actions → Android**, clique na execução mais recente (verde) do branch e baixe o artefato **jarvis-fase0-debug-apk** (um .zip com o .apk).
2. Copie o `.apk` para o celular e abra. O Android vai pedir para permitir "instalar apps desconhecidos" para o app usado (Arquivos/Chrome).
3. Abra o app **JARVIS**. A tela é o *painel da Fase 0*.

## 2. Permissões (cartão "Permissões")

Toque em **Abrir** em cada item até todos ficarem ✅:
- Exibir sobre outros apps
- Notificações
- Microfone e câmera
- Bateria sem restrições

No Redmi (HyperOS), ative também, nas telas que os botões abrem:
- **Inicialização automática** → ligado
- **Outras permissões → Abrir novas janelas enquanto executa em segundo plano** → permitir
- **Economia de bateria** do app → **Sem restrições**

## 3. Roteiro de testes

| PoC | O que fazer | Critério de saída (roteiro) |
|---|---|---|
| **0.1 Overlay** | Toque em **Ligar personagem**. Use o celular normalmente por 24 h (bloquear, desbloquear, abrir apps). | Fica vivo 24 h; zero "mortes inesperadas" no resumo |
| **0.2 Toque** | Com **outro app** aberto, toque no personagem. A sessão abre, testa microfone e câmera e mostra o resultado. Feche e repita **20 vezes** em apps diferentes. | ≥ 95% de sucesso |
| **0.3 LLM local** | Baixe um modelo `.litertlm` (ver abaixo), toque em **Importar**, escolha GPU e rode **Teste rápido**; depois CPU. Rode **5 min** uma vez para ver aquecimento. | Medir: carga, 1º trecho, trechos/s, RAM (PSS), temperatura |
| **0.4 Voz** | Ative o **modo avião**. Toque em **Ouvir** e leia cada uma das 20 frases. Depois **Testar voz**. | Medir: WER e latência |
| **0.5 Personagem** | Deixe o personagem ligado por 1 h. Toque nele algumas vezes. | ≤ 30 fps; reação ao toque < 100 ms; sem impacto perceptível na bateria |

Ao final, toque em **Compartilhar** (cartão "Relatório") e cole o texto em [`benchmarks.md`](benchmarks.md) — ou me envie, que eu registro e analiso.

### Modelos para a PoC 0.3
Os modelos ficam na comunidade LiteRT do Hugging Face (é preciso aceitar a licença Gemma logado na conta):
- **Gemma3-1B-IT** (`litert-community/Gemma3-1B-IT`) — pequeno, bom primeiro teste.
- **Gemma 3n E2B** (`google/gemma-3n-E2B-it-litert-lm`) — a classe "E2B" prevista no roteiro.
- **Gemma 4 E2B** (`litert-community/gemma-4-E2B-it-litert-lm`) — mais recente.

Baixe o arquivo `.litertlm` e use **Importar**. Arquivos grandes também podem ser copiados por cabo para `Android/data/com.joctaeng.jarvis/files/models/`.

## 4. O que está no código

| Parte | Onde | Testado como |
|---|---|---|
| Contratos `LlmProvider`, `CharacterRenderer`, `Tool` (seção 5.3) | `core/contracts` | compilação |
| Regras do orquestrador (7.2) + fallback honesto | `mind/orchestrator` | 12 testes de unidade |
| Policy Engine (10.2), Tool Gateway, Audit Log | `action/gateway` | 8 testes de unidade |
| Orçamento de RAM e escolha de modelo (3.2/3.3) | `system/resources` | 6 testes de unidade |
| Encaixe nas bordas, posição por app (6.4) | `presence/placement` | 5 testes de unidade |
| Cérebro local LiteRT-LM | `mind/provider-local` | PoC 0.3 no aparelho |
| Overlay, sessão de toque, voz, painel | `app` | PoCs no aparelho + teste do WER |

Decisões registradas em [`ADR/`](ADR/).

## 5. O que ainda falta na Fase 0
- **PoC 0.4b:** comparar com sherpa-onnx (ADR 0005).
- **Personagem Rive:** depende da arte (ADR 0004).
- **Preencher `benchmarks.md`** com os números reais — só o aparelho pode dar.
