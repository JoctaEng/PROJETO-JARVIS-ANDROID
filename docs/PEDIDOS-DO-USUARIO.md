# Pedidos do usuário — situação e fase prevista

Registro único dos pedidos do Prof. Joctã. Atualize a coluna "Situação" a cada entrega. Fases seguem `docs/ROTEIRO.md`.

| # | Pedido | Situação (v0.6.0) | Fase |
|---|--------|-------------------|------|
| 1 | Não cortar minha fala no meio | **Feito, falta medir no aparelho**: `SpeechListener` junta trechos se eu voltar a falar em até 1,1 s e sugere silêncio maior ao reconhecedor | 1 |
| 2 | Resposta falada com pouco atraso | **Parcial**: causa provável (Gemini TTS por frase, sem streaming; queda para Kokoro mais lento) ainda **não medida**. Falta instrumentar (motor + ms por frase), desligar o Gemini por alguns minutos após falha e testar streaming | 1 (medir) / 2 |
| 3 | Conhecimento completo de si ("como o OpenClaw, só que mais avançado") | **Feito (base)**: `SelfKnowledge` monta, a cada pedido, versão, cérebro, voz, autonomia, ferramentas reais e o que ainda não existe; regra de caráter corrigida. Falta ferramenta `sobre_mim` e documento de ajuda embutido | 1 base / 2 |
| 4 | Mais sensação 3D e fluidez | **Parcial**: fusão entre expressões (só arte alinhada), balanço lento independente da boca, paralaxe do olhar. 3D de verdade (modelo/Filament) fica para depois | 1 parcial / 4+ |
| 5 | Recolhido = "risquinho" discreto, não portal grande | **Feito**: janela encolhe para 64×28 dp, desenhada como galáxia em disco vista de lado (inclinação ~14°), 0 fps recolhido, toque nela volta; recolhe por inatividade só se não estiver ouvindo/pensando/falando/com chat aberto | 1 |
| 6 | Conversar por voz sem sempre abrir a caixa de texto | **Pendente**: exige sessão de voz sem tela (microfone em segundo plano é restrito no Android — ADR 0001) e legenda no overlay | 2 |
| 7 | Tocar nele: cresce, vem para perto, olha para mim | **Feito**: ao tocar/ouvir/falar ele cresce de 90% para 100% e olha de frente por 6 s | 1 |
| 8 | Controlar quase todo o celular, com fluidez | **Pendente**: exige AccessibilityService (ler tela, tocar, rolar, voltar) com política de risco; começa pela Fase 3 | 3 |
| 9 | Modo trabalho: de costas, minimizado em cima/embaixo, volta sozinho ou ao ser chamado | **Pendente**: precisa de arte de costas/olhando para a tela e do controle do item 8 | 3 |
| 10 | Alternar ouvir e falar com fluidez (espera pausa, volta a ouvir) | **Parcial**: conversa contínua + tolerância a pausas. Falar por cima (barge-in) exige cancelamento de eco | 2 |
| 11 | "Tchau / até logo": ele se recolhe | **Feito**: `DismissCommands` (testado) encerra a conversa por voz e recolhe | 1 |
| 12 | Acordar chamando pelo nome | **Pendente**: palavra de ativação (microfone sempre ligado, bateria e privacidade) | 2–3 |
| 13 | Fechar apps que ele abriu | **Pendente**: Android não deixa fechar app de terceiros; opção viável: voltar à tela inicial / ação global da acessibilidade | 3 |
| 14 | Arte: 3 Joctãs, Joctã Casual (`ouvindo`/`preocupado`), regerar Luna/Thor alinhados, 72 imagens do Canva | **Pendente (usuário + download)**; validar com `tools/arte/verificar_alinhamento.py` | 2 |
| 15 | Chave de assinatura fixa | **Conferir**: o CI agora falha se o determinismo não existir. Comparar a linha "SHA-256 do certificado:" entre dois builds | 1 |
| 16 | EduMath com ponte do Euno | Código na branch `claude/euno-mcp` do EduMath; falta compilar no Windows com a keystore do professor e testar | 1 |

## Cérebro local lento (v0.6.1)
Causa provável: cada mensagem enviava ao Qwen3-4B o prompt inteiro (regras, dossiê de até 4.000 caracteres, lista completa de ferramentas e 20 mensagens de histórico). Na primeira palavra, isso é leitura pesada para a CPU do celular.
Feito na 0.6.1: prompt local com dossiê de até 800 caracteres, histórico de 8 mensagens, ferramentas só quando o pedido sugere ação (`ToolIntent`), e medição por turno no diagnóstico (`poc03_local_llm`: `prompt_chars`, `first_ms`, `total_ms`).
Próximo passo depende dos números medidos: se `first_ms` continuar alto, testar um modelo menor (Qwen3-1,7B) ou ajustar as threads do motor.
