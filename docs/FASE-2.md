# Fase 2 — "Ele me conhece e me ajuda de verdade" (em andamento)

Critérios de saída (roteiro): "Como está meu dia?" responde com dados reais da agenda · "Fechar o dia" executa como skill · o personagem desvia de botões e teclado em ≥ 5 apps.

## Feito (v0.7.0)
- **Agenda real**: ferramenta `agenda_consultar` (hoje / amanhã / 7 dias) lê o Google Agenda e as demais contas sincronizadas pelo `CalendarContract` — sem OAuth, sem Google Cloud. Só leitura. Permissão em Meu Euno → "O que ... pode fazer" → **Permitir ler a agenda**. Formatação e intervalos testados em `system:resources` (`AgendaTest`).
- **Comandos de voz sem passar pelo cérebro** (`VoiceCommands`): "tchau", "até logo", "pode ir"… recolhem; "para de ouvir", "encerrar", "chega"… só param de ouvir.
- **Voz do Gemini** com leitura robusta e diagnóstico; **registro de erros** e botão de relatório (v0.6.x).

## Próximos passos (nesta ordem)
1. Contatos (leitura) e notificações/app em primeiro plano como contexto (exigem permissões e, para notificações, o usuário ativar o acesso).
2. **Skills**: formato em arquivo + executor; primeira skill "Fechar o dia".
3. Memória completa (episódica + semântica com embeddings).
4. AccessibilityService: posicionamento inteligente, leitura de tela, voltar à tela inicial / fechar apps abertos pelas ferramentas.
5. Visão (câmera sob demanda + OCR), voz neural offline mais rápida, 3D opcional.

## Como testar a agenda
1. Instale por cima (a assinatura é conferida pelo CI).
2. Meu Euno → "O que ... pode fazer" → Permitir ler a agenda.
3. Pergunte: "como está meu dia?", "tenho algo amanhã?", "o que tenho esta semana?".
4. Se algo falhar, **Enviar relatório de erros** na tela inicial.

## v0.8.0 — fluidez da conversa (etapa 1 da ordem combinada)
Paciência ajustável, fila de mensagens (nada se perde enquanto ele responde), interrupção ("pera aí"), escuta de comandos durante a fala (experimental, com opção), voz do Gemini com cota diária contada e frases agrupadas.
Próximas etapas: (2) conversa por voz sem a janela do chat, com balão de legenda no personagem; (3) controle do celular por acessibilidade.

## Histórico por versão (a partir da v0.9.0)
- **0.9.0** (build 41): cérebro do celular com prompt/histórico enxutos; botão **Nova** e **Resumos de conversa**; seção **Conversa** nos Ajustes (paciência, ouvir comandos, bip, conversa no relatório); chat com teclado aberto usa a tela toda; descanso por cota persistente.
- **0.10.0** (build 42): modo **legenda** (balão sem abrir o chat); chamado **"Oi Joca"** (opcional).
- **0.11.0** (build 48): relatório com resumo de todos os erros e log do Android; escuta registrada passo a passo; "Oi Joca" solta o microfone; tentativa automática em falhas do reconhecedor; espera da cota do Gemini lida do que o Google diz; ferramentas de memória; limpeza de cifrões e etiquetas de emoção; WhatsApp por pacote; **Teste completo**; toque longo → **fechar por completo**.
- **0.12.0** (build 50): cérebro local só quando escolhido ou sem internet; voz do Gemini com menos pedidos e contador real; um reconhecedor por vez; mute do bip com acesso a Não perturbe; **whatsapp_mensagem**, **agenda_criar**, **resumo_do_dia**.
- **0.13.0** (build 51, **não instala** no aparelho do usuário): controle do celular por acessibilidade; memória com categorias, edição e busca.
- **0.13.1** (teste de instalação): igual à 0.13.0 sem declarar o serviço de acessibilidade.
- **0.13.2**: declara `ACCESS_NOTIFICATION_POLICY` para o Euno aparecer em "Acesso aos modos" (Não perturbe). Ainda sem o serviço de acessibilidade (teste de instalação).
- **0.13.3** (teste): serviço de acessibilidade com declaração mínima + permissão de Não perturbe.
- **0.14.0** (teste): registro por dia sem corte; exportar relatório completo (.txt/PDF) e relatório curto; conversas gravadas e exportáveis; tela **Testar funções**; `abrir_app` com nome flexível; confirmação real só para desinstalar/excluir/enviar/Pix/pagamento; botão direto para a acessibilidade do Euno.
- **0.15.0** (teste): leitura da tela sempre de OUTRO app (lista de janelas, ignora as do Euno) + **modo "agindo na tela"** (personagem pequeno no canto superior esquerdo, legenda mínima, resposta por áudio); mensagem certa quando a acessibilidade está ligada mas desconectada; "E aí, tudo bem" não vira despedida; prompt do agente: tentar outra via, não inventar, enviar mensagem tocando em "Enviar" com confirmação; até 8 ferramentas por resposta; "Testar funções" atualizado (WhatsApp por pacote, contatos sem telefones, ler a tela da Agenda).
- **0.16.0** (teste): ferramentas aceitam argumentos com outros nomes/formatos (`ArgFixer` + `parseCall` tolerante) e o erro mostra o formato certo; `abrir_app` espera o app aparecer e "WhatsApp" abre o Business; **conversa por voz fora da tela de conversa** (`VoiceSession`: continua ouvindo quando o Euno abre outro app, comandos durante a fala); **balão de legenda por cima de qualquer app** (ouvindo/dizendo, confirmação, Parar/Abrir/Fechar); tocar no personagem durante a ação = parar; testes de tocar/rolar consertados.
- **0.17.0** (teste): **voz humana do Azure** (1ª no Automático: Azure → Gemini → Piper/Kokoro → Android, com contador mensal); **Piper offline** (Faber, Cadu, Jeff); **Groq/Cerebras** grátis como cérebro principal ou **reserva automática**; ferramentas só entram no prompt quando o pedido é de ação; tela **Configurar IA e voz** (passo a passo, campos, testar, baixar); correções: não inventar valores, abrir conversa do WhatsApp, agenda salva sozinha, EduMath espera abrir, "pare" tolerante, fechar app = início, Acessibilidade sem queda, relatório da versão atual primeiro.
- **0.18.0** (teste): **vários cérebros ao mesmo tempo** (Gemini, Groq, Cerebras...), cada um com chave, teste e ordem, na mesma lista em Meu Euno e em Configurar IA e voz; cérebro que bate limite descansa e o próximo responde; **deslizar para os lados** (`tela_deslizar`); agenda Xiaomi salva no "OK"; não diz que fez antes de confirmar; Piper mostra por que o download parou; instruções do Azure corrigidas.
- **0.19.0** (teste): **avatar falante** — a boca acompanha o som de cada letra e o volume real da voz (todas as vozes), o rosto muda com a emoção de cada frase, e no Joctã Casual só a boca se mexe sobre a expressão (ver `docs/PESQUISA-AVATAR-FALANTE.md`).
- **0.20.0** (teste): **avatar 3D** (VRM) na tela: fala com a boca sincronizada, pisca, respira, olha e mostra a emoção de cada frase; dá para importar um avatar `.vrm` próprio (ADR 0012).
- **0.21.0** (teste): **Joctã Casual e Luna em 3D** (falam com a boca sincronizada e mostram emoções); o avatar 3D acompanha o personagem escolhido; o avatar de exemplo continua disponível.
- **0.25.0** (teste): **avatar igual em todo lugar** — com 3D ligado, o desenho 2D nunca aparece por trás (portal, "tchau", troca de avatar); o 3D entra e sai pelo portal com animação; chat, tela inicial e sessão de toque mostram o rosto do MESMO avatar 3D (o importado ganha miniatura própria); Guardião 3D com busto fechado no rosto. **Agente mais humano**: vai para o canto antes de agir; espera o app carregar; rola no ritmo de uma pessoa (4 direções) e com "procurar" rola sozinho até achar; **vê o print da tela** (`tela_ver`, Gemini) e toca no ponto (`tela_tocar_ponto`); se o app recusar o toque, toca com o dedo virtual; tentativas em silêncio (só a resposta final é falada; um só aviso "Agindo… N passos"); confere as próprias ferramentas antes de dizer "não consigo"; cérebro que volta vazio descansa 90 s e o próximo responde na hora; aviso na tela inicial quando o sistema fecha o Euno (bateria/início automático). A permissão nova de print faz o Android desligar a acessibilidade uma vez.
- **0.25.1** (teste): **cada avatar sabe quem é** — nome, gênero e jeito próprios (`AvatarPersonas`; o Guardião 3D usa o perfil do Guardião), sem herdar o personagem 2D; voz padrão pelo gênero do avatar; atende por **"Oi Joca"** (chamado geral) **ou "Oi <nome do avatar>"** (`WakeWord.matchAny`).
