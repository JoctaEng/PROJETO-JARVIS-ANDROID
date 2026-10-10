# Pesquisa: cérebro e voz mais baratos/gratuitos (10/10/2026)

Objetivo do usuário: IA eficiente e inteligente sem gastar cotas do Gemini à toa; voz bem humana, mais leve e rápida; alternativas gratuitas ou mais baratas.
Fontes na maioria de terceiros (blogs/agregadores); números de cota mudam com frequência — conferir na página do provedor antes de confiar.

## Diagnóstico das cotas (relatórios da 0.14–0.16)
- O limite que estoura é o da **voz do Gemini** (100 pedidos/dia no modelo de voz; 196 no dia 09/10), não o do cérebro.
- O cérebro manda ~23 mil caracteres de instruções a cada turno (38 ferramentas sempre incluídas).

## Cérebro (texto)
| Opção | Custo | Observações |
|---|---|---|
| Gemini Flash-Lite (atual) | grátis com limite | Guias de set/2026 citam 500–1.000 pedidos/dia no Flash-Lite; Google não publica o número fixo (ver AI Studio) |
| **Groq** (OpenAI-compatível) | grátis com limite | ~30 pedidos/min, ~1.000/dia, ~200 mil tokens/dia em gpt-oss-120b/20b e Qwen (tracker de out/2026); muito rápido |
| **Cerebras** (OpenAI-compatível) | grátis (instável) | fontes conflitam: pode ser trial; muito rápido |
| OpenRouter `:free` | grátis | 20/min e 50/dia (1.000/dia com US$ 10 de crédito); lista muda |
| No celular: **Gemma 4 E2B** (LiteRT-LM) | grátis/offline | ~2,6 GB; chamada de ferramentas nativa; 47 tok/s num topo de linha (S26 Ultra) — no Redmi será mais lento; português não confirmado nas fontes |
| No celular: Qwen3 0.6B/1.7B | grátis/offline | pequeno; bom em ferramentas segundo um teste independente |

## Voz
| Opção | Onde | Custo | Observações |
|---|---|---|---|
| Gemini TTS (atual) | nuvem | grátis com 100/dia | muito natural; é a cota que acaba |
| **Azure Neural (pt-BR)** | nuvem | 500 mil caracteres/mês grátis (fontes de 2026; conferir), depois ~US$ 16/1M | vozes pt-BR humanas e rápidas; precisa chave Azure |
| Google Cloud TTS (Chirp 3 HD / Neural2) | nuvem | fontes citam 1M car./mês grátis; exige conta de cobrança | conflitante entre fontes |
| **Piper pt_BR** (VITS/ONNX) | celular | grátis | leve e rápida; qualidade média; roda no sherpa-onnx que o app já usa |
| Kokoro pt-BR (atual) | celular | grátis | vozes pf_dora/pm_alex/pm_santa; no Redmi ficou lenta (757% do tempo do áudio) |
| **Supertonic 3** | celular | grátis (MIT) | ~99M parâmetros, 31 línguas incluindo `pt`; ONNX; ~400 MB; sem SDK Android oficial encontrado; pt-BR x pt-PT não confirmado |
| Qwen3-TTS 0.6B | celular | grátis | inclui português; pesado para celular |

Fontes: openrouter.ai/blog/tutorials/free-llm-apis-compared, ianlpaterson.com/blog/free-llm-api-2026, gravity.fast/data/free-llm-api-tiers, memetik.ai/guides/gemini-api-free-tier-limits, aifreeapi.com/en/posts/gemini-api-free-tier-complete-guide, ai.google.dev/edge/litert-lm/overview, github.com/Leuconoe/LiteRT-LM-Unity (docs/llm-details.md), texttolab.com/blog/azure-text-to-speech-pricing, azure.microsoft.com/pricing/details/speech, costbench.com (Google TTS), huggingface.co (supertonic-3, rhasspy/piper-voices), picovoice.ai/blog/on-device-tts, bentoml.com (open-source TTS 2026).
