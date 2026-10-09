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
