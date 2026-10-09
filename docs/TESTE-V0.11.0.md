# Teste da v0.11.0 (build 48) — análise do relatório do usuário (09/10/2026)

Status: **análise apenas**. Nenhuma correção foi iniciada; aguardando a ordem do Prof. Joctã.
Marcas: **[DADO]** = está no relatório; **[HIPÓTESE]** = interpretação a verificar no código ou no aparelho.

## 1. O que funcionou
- [DADO] O relatório agora traz o resumo de todos os erros (773 ocorrências em 25 tipos), com contagem e horários. O detector de erros completo está funcionando.
- [DADO] A área "Teste completo" está ligada: `ouvirEnquantoFala=true`, "Otimização de bateria ignorada: sim".
- [DADO] A escuta se recupera sozinha: "tentando de novo sozinho (1ª vez)" e, depois de 2 falhas do reconhecedor do aparelho, passou para o padrão. Em 00:10:48–00:11:25 a conversa continuou depois das falhas.
- [DADO] O relatório mostra a causa da cota do Gemini (HTTP 429, "retry in 20h49m", limite 100 por dia no modelo gemini-3.8-flash-tts).

## 2. Problemas, por impacto
1. **Voz natural bloqueada pela cota diária do Gemini (o problema maior).**
   - [DADO] Google registrou o limite de 100 pedidos por dia; o app contava 28. Divergência de 72.
   - [HIPÓTESE] O contador do app não conta as tentativas que falham. A falha tenta `generateContent` e depois `interactions`, então cada frase pode gastar 2 pedidos no Google, e o app conta só 1. Também pode haver uso da mesma chave fora do app (testes no AI Studio, outro projeto).
   - [DADO] Eram 299 frases com "sem áudio natural (0 ms)": a voz do Android fez quase toda a leitura.
   - [HIPÓTESE, bug] O descanso de 6 h começou às 22:26, mas houve pedidos reais ao Gemini às 00:10 (pedidos 27 e 28). O descanso não foi respeitado nesse intervalo. Verificar a leitura de `geminiTtsSkipUntil` na inicialização do `VoiceOutput`.
   - [DADO] Kokoro marcado como lento: 757% do tempo do áudio. Não é uma alternativa neste aparelho.
2. **Cérebro de texto na nuvem bloqueado pelo teto de gasto mensal do projeto Google.**
   - [DADO] 18:53 e 20:51: "Your project has exceeded its monthly spending cap" (gemini-3.1-flash-lite). O app caiu para outro modelo.
   - [DADO] Ação do usuário: revisar o teto em AI Studio (spend cap). Mudar o plano ou o teto tem custo; decisão do usuário.
3. **Mute do bip não funciona (375 avisos).**
   - [DADO] "não consegui silenciar o bip (Not allowed to change Do Not Disturb state)". No Android 16, mudar o volume de notificação/sistema exige acesso de "Não perturbe". A função está inerte e polui o relatório.
   - [HIPÓTESE] O bip não é de notificação. Para testar, é preciso saber qual fluxo o reconhecedor usa neste aparelho.
4. **Reconhecedor em conflito: BUSY (8) e SERVER_DISCONNECTED (11).**
   - [DADO] BUSY ×11 e SERVER_DISCONNECTED ×2 no reconhecedor padrão (11 = ERROR_SERVER_DISCONNECTED; 8 = ERROR_RECOGNIZER_BUSY).
   - [HIPÓTESE] Há mais de um `SpeechRecognizer` ativo ao mesmo tempo: o "Oi Joca" (solta o microfone, mas o destroy é assíncrono), a conversa e o "ouvir comandos enquanto fala", que recria o reconhecedor a cada ~0,7 s quando dá erro.
5. **"Oi Joca" rodou 3,5 h (20:54 → 00:25) e a bateria caiu para 43%.**
   - [DADO] 305 avisos de bip durante a escuta do chamado.
   - [DADO] Não há "chamado reconhecido" nas linhas visíveis do relatório. Não dá para dizer se alguém chamou o Euno nesse período.
   - Gasto de bateria sem medição; é preciso medir antes de concluir.
6. **Cérebro local (Qwen3-4B) ainda estoura o contexto.**
   - [DADO] "A conversa ficou longa demais para a IA do celular" ×14, entre 16:31 e 16:42.
   - [HIPÓTESE] Corte por número de mensagens não basta; o corte precisa ser por tokens, com um resumo da parte cortada.
7. **OneKeyClean encerrou o app duas vezes (14:42 e 21:35), mesmo com bateria "sim".**
   - [DADO] "processo anterior encerrado: OneKeyClean". A isenção de bateria não impede o limpador do HyperOS.
   - Ação do usuário: "Sem restrições", início automático e trava do app nos recentes.
8. **WhatsApp não encontrado entre 145 apps, sem nomes parecidos.**
   - [DADO] `abrir_app: "WhatsApp" não encontrado entre 145 apps; parecidos: nenhum`.
   - [HIPÓTESE] O WhatsApp não aparece na lista de apps lançáveis (pode ser clone, "Segundo espaço" ou nome diferente). Precisa de confirmação do usuário: o que aparece na gaveta de apps?
9. **Frases longas e tempos.**
   - [DADO] "voz do Android não terminou em 20 s" ×1 (16:41). O limite proporcional da v0.9.0 já deveria cobrir isso; conferir.

## 3. Perguntas que dependem do usuário
1. Quer revisar o teto de gasto do Google (AI Studio) ou aceitar a voz do Android até a cota renovar?
2. Quer dar acesso de "Não perturbe" ao Euno para o mute do bip, ou tirar essa função?
3. O WhatsApp aparece na gaveta com qual nome? Está instalado como clone ou em outro espaço?
4. "Ouvir comandos enquanto ele fala": manter ligado para teste, ou desligar até o conflito do reconhecedor ser corrigido?
5. "Oi Joca": manter ligado (gasta bateria) ou desligar nos próximos testes?

## 4. Próxima versão (v0.12.0), só depois da ordem do usuário
- Um único reconhecedor por vez, com liberação antes de qualquer novo início; barge-in e "Oi Joca" usam a mesma fila.
- Contador da voz do Gemini contando cada tentativa HTTP; respeitar o descanso na inicialização; mostrar no relatório a divergência com a cota do Google.
- Mute do bip: remover ou pedir o acesso de "Não perturbe".
- Corte do histórico local por tokens, com resumo do trecho cortado.
- Medição de bateria da escuta do chamado no relatório (tempo ligado e ciclos).
- Diagnóstico do WhatsApp: listar pacotes que contenham "whatsapp".

## 5. v0.12.0 — Etapa A (implementada; só compila no CI)
Decisões do usuário: **sem paciência para o cérebro local** (muito lento); **dar acesso a Não perturbe**; manter tudo ligado; quer **melhorias e funções novas**, não só correções. Conta Google: **Tier 1** (a mensagem de erro do Google diz isso), com limite de 100 pedidos/dia no modelo de voz.

Correções (curtas):
- Cérebro do celular só entra se escolhido (local primeiro/somente), se não houver cérebro online ou se não houver internet; deixa de ser a reserva de qualquer erro da nuvem. Histórico local cortado por tamanho (~3.000 caracteres), não só por mensagens.
- Voz do Gemini: frases juntas em até 700 caracteres por pedido (antes 280); não tenta a 2ª forma de pedido quando a 1ª dá 429; o contador conta cada pedido HTTP real; "Testar voz" não reabre a cota diária (cada teste gastava mais um pedido).
- Reconhecedor de voz: um ouvinte por vez (conversa > ouvir comandos > "Oi Joca"); a continuação reaproveita o mesmo reconhecedor (recriar logo depois dava ERROR_SERVER_DISCONNECTED em ~42 ms); espera de 350 ms antes de criar outro depois de destruir um; "Oi Joca" tem tempo limite de 20 s.
- Silenciar o bip só tenta com o acesso a Não perturbe; botão "Dar acesso a Não perturbe" em Teste completo; sem o acesso, avisa uma vez em vez de 375 vezes.

Novidades:
- `whatsapp_mensagem`: abre a conversa do contato (WhatsApp ou Business) com o texto escrito; o usuário toca em enviar.
- `agenda_criar`: abre a agenda com título, data e hora preenchidos; o usuário salva.
- `resumo_do_dia` ("bom dia" / "fechar o dia"): agenda de hoje e de amanhã, bateria e memórias recentes, para o Euno narrar.
- Lógica pura nova com testes: `PhoneNumber`, `EventTime`, `HistoryTrim`.

## 6. v0.13.0 — Etapa B (implementada; só compila no CI)
- **Controle do celular por acessibilidade** (`EunoAccessibilityService`, desligado até o usuário ligar em Ajustes → Controle do celular e no Android): ferramentas `tela_ler`, `tela_tocar`, `tela_digitar`, `tela_rolar`, `tela_navegar`. Senhas nunca são lidas nem preenchidas; botões enviar/pagar/comprar/apagar etc. exigem `confirmado=true` depois de o usuário confirmar; Modo Privado nega. Lógica pura testada em `ScreenText`.
- **Memória completa:** categorias (família, pessoas, trabalho, preferências, saúde, casa, geral), editar e acrescentar à mão em Minha Memória, busca, ferramenta `memoria_buscar`, memórias agrupadas por assunto no prompt.
- **Teste completo** inclui o controle do celular e mostra o que falta ligar no Android.
- O Euno passa a saber se o controle do celular está ligado.

## 7. Falha de instalação da v0.13.0 (build 51)
- [DADO] Print do usuário (09/10, 9:17): "Euno — O app não foi instalado." ao instalar `Euno-fase2-v0.13.0-build51.apk` (149,94 MB). A v0.12.0 (build 50) **instalou** normalmente.
- [DADO] O CI da build 51 passou, inclusive "Conferir assinatura do APK (atualizar por cima, sem desinstalar)".
- [HIPÓTESE] O que mudou de 50 para 51 no manifesto é a declaração do serviço de acessibilidade (`EunoAccessibilityService`, permissão BIND_ACCESSIBILITY_SERVICE, `res/xml/euno_accessibility.xml`). O Android só devolve "não foi instalado" genérico; o motivo real não aparece.
- Experimento (v0.13.1, build seguinte): igual à 0.13.0 mas **sem declarar o serviço** no manifesto. Se instalar, a causa está na declaração do serviço; se não, está em outro ponto da 0.13.0 (ou no espaço/armazenamento do aparelho).

## 8. "Não perturbe": o Euno não aparecia na lista (09/10, prints do usuário)
- [DADO] Em Configurações → Acesso aos modos (Não perturbe), a lista mostra Telefone, Gmail, Google… mas **não o Euno**; o aviso "falta liberar o acesso a Não perturbe" fica para sempre em Teste completo.
- Causa (erro meu): o manifesto não declarava `android.permission.ACCESS_NOTIFICATION_POLICY`; só apps que a declaram aparecem na lista.
- v0.13.2: permissão declarada. Depois de instalar, o Euno deve aparecer na lista. (Continua sem o serviço de acessibilidade, por causa do teste de instalação da 0.13.1.)

## 9. Resultado do teste de instalação (09/10/2026)
- [DADO] O usuário informou que instalou o APK 52 (v0.13.1, sem declarar o serviço de acessibilidade) e **instalou**. A v0.13.0 (build 51, com o serviço) **não** instalou ("O app não foi instalado").
- Conclusão: a causa está na declaração do serviço (`EunoAccessibilityService` no manifesto e/ou `res/xml/euno_accessibility.xml`). O motivo exato do Android não é conhecido.
- Próxima tentativa prevista (só com ordem do usuário): declarar o serviço com a configuração mínima (sem `isAccessibilityTool` nem atributos novos); alternativa: ler o erro real com `adb install`. Nenhum APK novo até o usuário mandar.

## 10. Dados do aparelho (09/10/2026)
HyperOS 3.0.302.0 (WNOMIXM.C07) · Android 16 (BP2A.250605.031.A3) · patch de segurança 2026-08-01 · armazenamento 260,1 GB livres de 512 GB. Falta de espaço descartada como causa da falha de instalação da 0.13.0.

## 11. v0.13.3 — teste da declaração mínima do serviço de acessibilidade (a pedido do usuário, 09/10)
- Mudanças em relação à 0.13.0 (que não instalou): `exported="true"` no serviço (forma da documentação); `euno_accessibility.xml` sem `isAccessibilityTool` e sem `accessibilityFlags`. Além disso inclui a permissão `ACCESS_NOTIFICATION_POLICY` (0.13.2) e está sem outras mudanças.
- Resultado esperado: se instalar, a causa estava nesses atributos. Se **não** instalar, a causa é a própria presença de um serviço de acessibilidade (provável restrição do instalador/sistema do aparelho) ou, menos provável, a permissão nova; nesse caso separar as duas mudanças e/ou ler o erro com `adb install`.

## 12. Causa da falha de instalação: Google Play Protect (print do usuário, 09/10/2026)
- [DADO] Ao abrir o APK da v0.13.3 (build 59) pelo Google Drive, o Android mostra **"Google Play Protect — O app foi bloqueado para proteger seu dispositivo. Esse app pode pedir acesso a dados sensíveis. Isso pode aumentar o risco de roubo de identidade ou fraude financeira."** Só há o botão "Entendi" (sem "instalar mesmo assim").
- [DADO] Sequência: 0.12.0 e 0.13.1 (sem serviço de acessibilidade) instalam; 0.13.0 e 0.13.3 (com serviço) não instalam. A mensagem genérica "O app não foi instalado" da 0.13.0 provavelmente era o mesmo bloqueio.
- [LEITURA, não verificada em fonte oficial] O bloqueio é uma proteção do Play Protect contra apps instalados fora da loja que pedem acesso sensível (acessibilidade, notificações, SMS), ativa em alguns países. Não é um erro de manifesto: mexer em atributos não resolve.
- Opções (decisão do usuário): (A) desligar temporariamente a análise do Play Protect para instalar e religar depois (risco: some a proteção enquanto desligado; eficácia **não confirmada** para este bloqueio); (B) tentar `adb install` (não confirmado se contorna); (C) abrir mão do controle por acessibilidade e seguir com a Etapa C; (D) distribuir pela Play Store (teste interno), demorado.
- Nenhum código novo até o usuário decidir.

## 13. Teste da v0.13.3 (build 59) com a acessibilidade ligada (09/10/2026, relato do usuário + relatório)
- [DADO] O usuário conseguiu instalar a 0.13.3 depois de desligar a análise do Play Protect (dica da opção A) e ligou o serviço "Euno - controle do celular". Perguntou **por que precisa ligar a acessibilidade** (a tela do Android lista gestos e observação de ações).
- [DADO, relato] Depois de ligar, **o celular ficou muito lento**, "falando e se movendo sozinho", sem conseguir fazer nada.
- [DADO] O relatório colado foi **cortado pelo limite de colagem** logo depois das 00:15; não chegaram as seções "Últimas ações das ferramentas" nem o registro do período da acessibilidade. Resumo: 57 pedidos de voz do Gemini hoje (descanso: não), 1 falha de cérebro às 09:30 (HTTP 503 do Gemini, sem outro cérebro porque o local só entra se escolhido), OneKeyClean encerrou o app às 09:21, processo encerrado por sinal às 11:41, 558 avisos do bip até 07:00 (versões anteriores, antes do acesso a Não perturbe).
- [HIPÓTESES, não verificadas] (1) o serviço de acessibilidade com `canRetrieveWindowContent` pesa no sistema do aparelho; (2) o agente chamou `tela_*` em laço (autonomia "agente pessoal" executa sem confirmar) e "mexeu sozinho"; (3) laços de escuta ("Oi Joca" + ouvir comandos) recriando reconhecedores. Falta o registro `[controle]` e as "Últimas ações das ferramentas" para saber.
- Medidas propostas (só com ordem): confirmar SEMPRE cada toque/digitação/rolagem da tela até o usuário liberar na sessão; limite de ações por pedido; botão "parar tudo" visível; registrar cada ação `[controle]` com horário.
