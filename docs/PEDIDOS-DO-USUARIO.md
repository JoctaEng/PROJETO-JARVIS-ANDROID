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

## Anotações do teste da v0.8.0 (08/10/2026) — a corrigir no próximo APK
Do relatório e do que o usuário contou:
1. **Cérebro do celular falha em todo turno** (preferência "Celular primeiro" + nuvem): o pedido grande da nuvem (27 ferramentas, 20 mensagens) é mandado também ao modelo local → "conversa longa demais" (código -6) → cai na nuvem. Montar pedido compacto só para o local; não pré-carregar à toa.
2. **Voz do Android corta frase longa**: `não terminou em 20 s` (frase de 407 caracteres). Tempo-limite proporcional ao tamanho e/ou quebrar trechos longos para a voz do Android.
3. **Cooldown do Gemini (cota 429) não sobrevive ao reinício do app**: persistir.
4. **`pesquisar_na_web` com "consulta vazia"**: aceitar variações do nome do argumento (query, pesquisa, busca, texto) e devolver erro útil ao modelo.
5. **Chat: "quando começo a escrever não consigo mais ver o texto"** — aguardando o print da tela; suspeita: teclado + linha extra de opções espremendo o campo.
6. **"Ouvir comandos enquanto ele fala" não funcionou** (mesmo com Conversa por voz + a opção ligadas): causa não confirmada. Registrar cada etapa (iniciou, falhou por quê, ouviu o quê) e mostrar na tela "ouvindo comandos…". Pode ser conflito de microfone/áudio com a própria voz dele; avaliar outra estratégia.
7. **Paciência (espera) difícil de achar**: está em Meu Euno → seção "Voz" (no meio de velocidade/tom). Criar cartão "Conversa" com paciência, fila e ouvir-enquanto-fala, e atalho dentro do chat.
8. **A conversa não aparece no relatório** (por privacidade só entram medições e o início dos pedidos de ferramentas). Avaliar opção "incluir minhas falas e respostas no relatório" (desligada por padrão).
9. Fila, "pera aí", "tchau" e contatos: ainda não confirmados em teste.
10. HyperOS: "Otimização de bateria ignorada: NÃO" persiste; falta o usuário aplicar o ajuste.
11. **Chat com teclado aberto** (prints do usuário): cabeçalho de 3 linhas + 2 chaves ocupam o cartão e o campo de texto vira uma faixa fina; a conversa some. Dar prioridade ao campo quando o teclado abre: esconder chaves, encurtar cabeçalho, altura mínima do campo.
12. **Bipe de ativação do microfone** (incômodo): vem do reconhecedor do Android a cada abertura (ao tocar no Joca, ao voltar a ouvir, entre trocas). Tentar silenciar brevemente o canal do bipe (não garantido no HyperOS); solução definitiva = captura própria + reconhecedor próprio (também habilita ouvir enquanto ele fala, com cancelamento de eco).
13. **URGENTE — Nova conversa e resumo de contexto**: hoje não há como iniciar conversa nova e o histórico (20 msgs + prompt ~12k car.) vai inteiro a cada pergunta. Criar: botão "Nova conversa" (salvar resumo ou começar do zero); área "Resumos de conversa" em Meu Euno (ver/editar/apagar/escolher); ao começar nova conversa o usuário escolhe se carrega resumos; resumo contínuo substituindo mensagens antigas para reduzir tokens/custo.
14. **Cota da voz do Gemini**: o pedido de voz leva só a frase falada (1 por frase); o contexto completo vai nos pedidos de TEXTO (outro modelo/cota). Contador de voz do dia passa a persistir entre reinícios; relatório mostra pedidos de voz e de texto separados.
15. Aguardando mais lembranças do usuário; lista a confirmar antes de implementar.

## Pedidos posteriores (08–09/10/2026) e situação
| # | Pedido | Situação |
|---|---|---|
| 17 | Chamar pelo nome ("Oi Joca") e o Euno começa a falar | Feito na v0.10.0 (opcional, em Ajustes → Conversa). Funcionou ao menos uma vez no teste. **Reconhecer a voz de quem chama: pendente (Etapa C, pesquisa).** |
| 18 | Conversar sem abrir a janela do chat | Feito na v0.10.0: modo legenda (balão), botão Expandir. Não confirmado em vídeo/teste |
| 19 | Detector de erros completo, log por log, em tempo real, sem perder o começo, incluindo o "Oi Joca" | Feito na v0.11.0: resumo de todos os erros, registro de cada passo da escuta com código e nome, log do Android. Verificado no relatório da v0.11.0 (773 ocorrências listadas) |
| 20 | Botão "Habilitar tudo para teste completo" com chaves e legendas de combinação | Feito na v0.11.0 (Ajustes → Teste completo); ampliado nas v0.12–0.13 |
| 21 | Toque longo no personagem com opção de fechar o Euno por completo | Feito na v0.11.0. Ainda não confirmado no aparelho |
| 22 | Sem paciência para o cérebro local (muita demora) | Feito na v0.12.0: o cérebro local só entra se escolhido ou sem internet |
| 23 | Dar acesso a "Não perturbe" e manter tudo ligado | Botão em Teste completo (v0.12.0); o acesso é dado pelo usuário no Android |
| 24 | Melhorias e funções novas, não só correções | Etapa A (v0.12.0): WhatsApp, compromisso, bom dia/fechar o dia. Etapa B (v0.13.x): controle do celular, memória completa. **Etapa C pendente:** visão, reconhecer a voz, rotina do dia automática, e-mail/notificações |
| 25 | Usar agentes/IA mais baratos e **não gastar os limites do dia e da semana** | Diretriz permanente. Até agora nenhum agente foi usado (passar o contexto custaria mais) |
| 26 | Dúvida: a API paga serve para a voz? | Resposta (09/10): a conta é Tier 1; limite de 100 pedidos/dia no modelo de voz `gemini-3.8-flash-tts`. Ver o limite real em ai.dev/rate-limit. **Decisão do usuário pendente** (subir de camada ou usar a voz do Android) |
| 27 | Teto de gasto mensal do Google | Usuário disse que já recusou o teto; revisar no AI Studio se o 429 de "spending cap" voltar |
| 28 | Registrar cada fase e os pedidos novos | Este arquivo, `docs/FASE-2.md` (por versão), `docs/TESTE-V0.10.0.md`, `docs/TESTE-V0.11.0.md` e `docs/HANDOFF.md` |
| 29 | Falha ao instalar a v0.13.0 ("O app não foi instalado"); a v0.12.0 instalou | **Em investigação:** v0.13.1 sem declarar o serviço de acessibilidade para isolar a causa |

### Etapa C (prevista, sem ordem ainda)
Visão ("o que é isto?" com câmera/tela); reconhecer a voz do usuário no "Oi Joca"; "Bom dia" e "Fechar o dia" automáticos em horário escolhido; e-mail e notificações; controle do celular por acessibilidade (volta quando a instalação for resolvida).
