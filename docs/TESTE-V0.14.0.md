# Teste da v0.14.0 (build 65) — conversa exportada pelo usuário (09/10/2026, 17:41–18:17)

Fonte: arquivo "Conversa com o Euno" exportado pelo próprio app (primeira exportação de conversa) + "Relatório do Euno.pdf" (relatório completo). Nomes de terceiros omitidos (repositório público).

## O que funcionou
- Exportar relatório completo e conversa em arquivo: ok (sem corte).
- `abrir_app` para WhatsApp Business e Agenda: ok. `tela_ler` leu o WhatsApp de verdade às 17:50 (linha do tempo de mensagens de voz) depois que o usuário fechou a janela do Euno.

## Falhas observadas (nada corrigido ainda; usuário mandou não criar)
1. **Lê a própria interface** (17:43, 17:44, 18:15): `tela_ler` devolveu o painel do Euno (inclusive o aviso "Bateria baixa…"). Causa: lê só `rootInActiveWindow`. Pedido 39.
2. **Inventa conteúdo** (17:45): disse que "vocês falaram de comida e ligação às 14:10" sem ter lido nada; só admitiu às 17:47. Precisa de regra: nunca afirmar o que não veio da ferramenta.
3. **Inventa regra de privacidade** (17:47): afirmou que `tela_ler` "só identifica botões" e não lê mensagens "por segurança"; às 17:50 leu o texto da tela normalmente. Prompt deve descrever o que a ferramenta faz.
4. **"Até logo!" no "E aí tudo bem"** (17:41:12, log: "comando de voz: despedida"): saudação tratada como despedida; `VoiceCommands.parse` precisa ser revisto.
5. **Responde a áudio ambiente** (17:47:55): o texto longo captado era outra pessoa/vídeo falando ("design craft… amanhã vai ter ônibus") e o Euno respondeu como se fosse com ele, sugerindo lembrete. Sem identificação de quem fala.
6. **Não age até o fim** (17:43): `whatsapp_mensagem` só abre a conversa com o texto preenchido; ele não tocou em Enviar (que exige confirmação do usuário) nem ofereceu o pedido de confirmação; mandou o usuário "tocar no botão".
7. **Não tenta outra via** (18:15): `tela_tocar` falhou com nome contendo emoji; chamou `tela_ler` e `abrir_app`, estourou o limite de ferramentas ("Parei: muitas ferramentas seguidas") e depois escreveu "agora sim! deve ter carregado" sem verificar (afirmação sem prova). Pedidos 40 e 41.
8. **Sugestão-chave ignorada** (17:56): o usuário propôs que, ao ler/agir na tela, o Euno se encolha para o canto superior esquerdo, diminua a legenda e responda por áudio para conseguir ler a tela. É a solução da falha 1 (pedido 39b); o Euno e a primeira análise trataram como preferência simples.

## Dados
- Voz do Gemini: 190 pedidos hoje (limite do modelo de voz: 100); voz do Android assumiu.
- Bip: "não consegui silenciar o bip" continua (acesso a Não perturbe não liberado para o Euno).

# Resultado de "Testar funções" (09/10/2026, ~18:42 e 18:49 locais; 2 arquivos iguais no Drive)

PASSOU: estado do celular; listar apps; abrir app "Google Agenda" (abriu "Agenda": correção do `abrir_app` confirmada); agenda de hoje; buscar contato; memória (guardar/buscar/esquecer); lanterna; voz (só vale se ouvida); microfone/reconhecedor; **acesso ao Não perturbe liberado**; cérebro (Gemini respondeu "ok" em ~1,5 s); relatório completo (~640 KB em <1 s; 2 dias de registro).

FALHOU:
1. **Acessibilidade: no APK=sim, ligado no Android=sim, conectado=NÃO.** Por isso `tela_ler/tocar/digitar/rolar` falharam. A mensagem das ferramentas diz "ainda não está ligado no Android", o que é **enganoso** neste caso (está ligado, só não conectou neste processo). Hipótese (não verificada): depois de "Fechar por completo" o HyperOS não reconecta o serviço sozinho; desligar e ligar de novo o serviço costuma resolver. Correção prevista: distinguir "desligado" de "ligado mas desconectado" e orientar o passo certo (e mostrar isso no teste).
2. **WhatsApp visível?** nenhum app com "whats" no nome. O WhatsApp Business abre por pacote (`com.whatsapp.w4b`), então o rótulo do app provavelmente não contém "whats" (hipótese: "WA Business"). O teste procura só por nome; deve procurar também pelos pacotes conhecidos.

Observações: o teste "Buscar contato" grava telefones reais no arquivo exportado; no futuro mostrar só a contagem. O teste de voz e o de tocar/digitar só provam algo com o serviço conectado.

# Teste da v0.15.0 (build 73) — relato do usuário e "Testar funções" (10/10/2026)

No Drive só chegou o resultado de "Testar funções" (arquivo "DOC-20261009-WA0030."). A conversa e o relatório completo da 0.15.0 **ainda não estavam lá**.

## Testar funções (0.15.0)
PASSOU: acessibilidade conectada; **ler a tela de OUTRO app** (leu `com.xiaomi.calendar`, nunca o Euno: correção funcionando); digitar; WhatsApp Business achado por pacote (rótulo do app não contém "whats"); abrir "Google Agenda"; demais ferramentas e cérebro (729 ms).
FALHOU, mas é defeito do teste, não do app: (a) "Tocar num botão" leu a Agenda porque o teste anterior abre a Agenda e volta à tela de testes sem esperar; (b) "Rolar a tela" falhou só no passo "cima" (já estava no topo; "baixo" funcionou).

## Relato do usuário (conversando de verdade)
Ao encolher para o canto superior esquerdo o Euno "trava": não ouve mais, não age, tocar nele não faz nada, não aparece caixa de mensagem; no começo funciona e depois não consegue abrir a Agenda. Nos testes tudo passa. Ele quer o comportamento do Gemini: analisar a tela e continuar ouvindo ao mesmo tempo.

## Causa encontrada no código (confirmada)
1. **A escuta e a legenda moram na `ChatActivity`.** `onStop()` chama `stopListening()` e `stopBarge()`, e a continuação por voz só roda em `repeatOnLifecycle(RESUMED)`. Quando o Euno abre outro app (WhatsApp, Agenda) a `ChatActivity` vai para segundo plano: **para de ouvir, para de ouvir comandos enquanto fala, e a legenda/confirmação somem**. O personagem (serviço) continua falando, mas não escuta. Já era assim antes da 0.15.0; o modo "agindo na tela" só deixou a falha visível.
2. **Tocar no personagem durante o modo agindo** abre a `ChatActivity`, que vê `acting=true` e mostra só o chip "Agindo na tela…": parece que "não faz nada" e não há botão de parar.
3. Os testes passam porque chamam as ferramentas direto, sem o cérebro. Na conversa real quem decide chamar a ferramenta é o modelo; **se o Euno diz que fez e não faz, só a conversa exportada da 0.15.0 mostra o porquê** (falta ver).

## Correção prevista (aguarda ordem)
- Mover a escuta e a conversa por voz para o **serviço do personagem** (não depende de nenhuma tela): continua ouvindo enquanto lê/age, inclusive comandos ("pera aí", "para").
- **Balão de legenda em janela de sobreposição** (visível por cima de qualquer app, com a confirmação e um botão "Parar"), em vez de depender da `ChatActivity`.
- Tocar no personagem em modo agindo = parar a ação e abrir o chat.
- Consertar os testes (esperar a tela de testes voltar; "rolar" só exige "baixo" ou "cima" com sucesso).

# Conversa e relatório completo da 0.15.0 (Drive, 10/10/2026; sessão 22:02–22:43 locais)

## O que funcionou
- Ler a tela de outro app e **tocar** ("Família … ❤️" tocado com sucesso, emoji ok), `tela_navegar` (início), `abrir_app` do WhatsApp Business, `agenda_consultar` (dia e semana), `contatos_buscar`. A camada "não lê o Euno" funcionou: o Euno disse sozinho "o sistema ainda está segurando minha visão só para as janelas do Euno" e abriu o app antes de ler.
- O usuário confirmou o problema de escuta (22:12) e a prova: quando voltou à tela inicial, ele voltou a ouvir (22:14).

## Falha nova e grave: ferramentas chamadas SEM argumentos
A partir de 22:08 (e sempre depois de 22:14) o registro mostra, para `abrir_app`, `agenda_criar`, `memoria_guardar`, `tela_tocar` e `whatsapp_mensagem`, só erros do tipo **"informe o nome do app" / "fato vazio" / "informe o título" / "informe o nome do botão" / "informe o contato e a mensagem"**: a ferramenta recebeu o pedido com os argumentos vazios (ou com outros nomes de campo). O modelo repete 2–3 vezes igual e desiste ("o sistema está teimoso"). Isso explica "não consegue abrir a Agenda" na conversa real enquanto "Testar funções" passa (o teste monta os argumentos certos). No começo funcionou (22:06 abrir_app, 22:10 tela_tocar com argumentos) e depois o modelo passou a errar o formato.
Causa provável (a confirmar com o registro dos argumentos, que hoje não existe): o protocolo de ferramentas é texto (`<tool_call>{"name":…,"arguments":{…}}`) e só aceita `arguments`/`parameters` com os nomes exatos dos campos; o modelo (Gemini lite) às vezes manda os campos soltos, com outro nome (`app`, `title`, `text`…) ou sem `arguments`. A mensagem de erro ("informe o nome do app") também não diz o formato certo, então ele não se corrige.

## Outros achados
- `tela_tocar` logo depois de `abrir_app` chegou antes do app aparecer ("não há outro app na tela") → `abrir_app` deve esperar o app ficar na frente.
- Voz do Gemini: 196 pedidos hoje, em descanso até 04:05; voz do Android falhou uma vez ("não terminou em 30 s", frase de 297 caracteres).
- O Euno explicou a falha de escuta com uma desculpa errada ("brigam pelo mesmo recurso"): a causa real é a `ChatActivity` parar ao abrir outro app.
- Erro dele de interpretação: tratou "abra por favor no meu WhatsApp" como app "WhatsApp" (não existe; é o Business) e pediu o nome; `abrir_app` devia aceitar "WhatsApp" → Business quando só ele existe.

## Correção prevista (aguarda ordem)
1. **Argumentos:** aceitar `args/input/params`, campos soltos e apelidos (`app`→`nome`, `title`→`titulo`, `text`→`fato/mensagem`…), com testes; registrar no relatório o formato recebido (nomes e tamanhos dos campos, sem o conteúdo); erro que diz o formato certo com exemplo.
2. **`abrir_app`** espera o app ficar na frente (até ~3 s) e trata "WhatsApp" como o Business quando só ele está instalado.
3. **Escuta no serviço + legenda em janela de sobreposição + botão Parar** (pedido 44).
4. Consertar os dois testes defeituosos.
