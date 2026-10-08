# Teste da v0.10.0 (build 42) — registro fiel do relatório

Fonte: relatório "Enviar relatório de erros" do Prof. Joctã, gerado ~18:47 (hora local) de 08/10, mais o vídeo de tela (ainda em upload no Drive; ver a última seção). **Este arquivo só registra. Nenhuma correção foi iniciada**, a pedido do usuário. Marcas: **[DADO]** = está no relatório; **[LEITURA]** = interpretação minha, ainda não confirmada.

## 1. Cabeçalho
- [DADO] Euno 0.10.0, Fase 2, código 42. Xiaomi 23090RA98G, Android 16 (API 36). Assinatura = a esperada (52:91:A0:…:59:5E), então a atualização entrou por cima.
- [DADO] Memória 5842 MB livres de 11385 MB. Bateria 39%. Otimização de bateria ignorada: **NÃO** (HyperOS pode fechar o app; pendente do lado do usuário).
- [DADO] Configuração: cérebro ONLINE_FIRST; nuvem GEMINI; voz AUTO; personagem jocta_casual; autonomia PERSONAL_AGENT; kokoroLento=true; paciência 3049 ms; **ouvirEnquantoFala=false**.
- [DADO] "Voz do Gemini hoje: 8 pedidos (limite 100)" e **"em descanso até 08/10 21:00"**.

## 2. Voz: toda a sessão saiu na voz do Android
- [DADO] Todas as frases do trecho visível (18:36–18:44) registram `sem áudio natural (0 ms); usando a voz do Android`. 0 ms = o Gemini nem foi tentado.
- [DADO] Isso bate com o descanso até 21:00 (guardado entre aberturas do app desde a 0.9.0) e com kokoroLento=true (Kokoro descartado).
- [DADO] Contador do dia = 8, bem abaixo de 100. O trecho do log em que o descanso foi decidido (a mensagem de erro do Gemini) está **cortado** ("…(início cortado)…"), então a causa exata (HTTP 429 de cota, qual cota, "retry in …") não aparece.
- [LEITURA] A cota que bloqueou não parece ser a diária de 100 contada pelo app. Pode ser limite por minuto/por modelo da conta, ou uso da mesma chave fora do app. Precisa do trecho inicial do log (ou do relatório logo após a falha) para saber. O app também trava a voz natural por até 6 h mesmo se o limite for curto, o que pode ter tornado a sessão inteira robótica.
- [DADO] Frases longas na voz do Android funcionaram sem corte (241 car. ≈ 17 s; 279 car. ≈ 19 s).

## 3. Cérebro online (Gemini) — tempos
- [DADO] 25 turnos em poc03, todos `provider=cloud`, prompt 12385–12845 car., 27 ferramentas, histórico 1→20 mensagens.
- [DADO] 1ª palavra: 839 ms a 3271 ms; maioria 1,0–1,5 s. Total: 0,96 s a 5,05 s (os mais lentos: 4461, 5049, 3920 ms, em sequência às 18:34–18:35 local).
- [LEITURA] O cérebro online não é o gargalo da percepção de lentidão; a voz do Android e o ciclo escutar→responder são.

## 4. Escuta (reconhecimento de voz)
- [DADO] Fala reconhecida várias vezes (3, 12, 13, 17, 21, 23, 31, 55, 70, 72, 94, 105, 291 caracteres). Uma com só «não» (3 car.) às 18:38:37.
- [DADO] 18:39:04 "sem fala: Não entendi". 18:41:00 idem.
- [DADO] 18:39:25 **erro de reconhecimento código 11**. [LEITURA] Pelo que conheço da API do Android, 11 = ERROR_SERVER_DISCONNECTED (serviço de reconhecimento desconectou); conferir na documentação antes de agir.
- [DADO] Ouvir comandos enquanto ele fala: desligado em todo o trecho (`ouvir comandos ao falar=desligado`). **O teste do "pera aí" durante a fala não aconteceu neste relatório.**

## 5. Fila de mensagens
- [DADO] 18:40:47 "mensagem na fila (1)" e 18:40:48 "respondendo as mensagens da fila juntas"; nova resposta com 1ª palavra em 1310 ms. Funcionou como desenhado.

## 6. Despedida ("tchau")
- [DADO] 18:41:50 «beleza tchau» → "comando de voz: despedida"; 18:44:48 «tchau» → idem. Ele disse "Até logo!" e a conversa por voz encerrou (falando=true→false em ~1,5 s).
- [DADO] Isso corrige a falha da 0.7.0.

## 7. "Oi Joca" (chamado)
- [DADO] 18:42:08 `chamado reconhecido (7 caracteres ouvidos, pedido junto=false)`; 6 s depois a fala «tá me ouvindo» foi reconhecida e respondida. **O chamado funcionou ao menos uma vez.**
- [DADO] O trecho cortado não mostra "escuta do chamado ligada", nem quantas ativações falsas/perdidas houve. O vídeo deve mostrar quantas tentativas ele precisou.
- [DADO] Bateria 38→39% durante ~10 min com o chamado ligado (pode estar carregando; não é prova de consumo).

## 8. Conversa (texto do chat incluído no relatório) — pontos observados
1. [DADO] "Que horas é agora" → "18:35" (hora certa naquele momento).
2. [DADO] Resumo do dia usou a agenda (UEBs, Ministério Público) corretamente via `agenda_consultar`.
3. [DADO] "O que você sabe sobre mim e sobre minha esposa": respondeu que não tem info pessoal; menciona "tarefas no EduMath" (origem não verificada).
4. [DADO] Contatos: achou "Meu Amor ❤️", mas mostrou o número como **`$98987177598$`** (cifrões visíveis). [LEITURA] O prompt manda toda fórmula em `$…$` (Persona.kt), e o número foi tratado como fórmula. O número `98987177598` tem DDD 98 + 987177598, ou seja (98) 98717-7598 (confirmado no relatório do Gemini, seção 17); a observação anterior de que faltava DDD estava errada.
5. [DADO] Abrir WhatsApp: ferramenta `abrir_app` falhou duas vezes ("WhatsApp" e "WhatsApp Business": "não encontrei um app chamado …"). O Euno disse "vou abrir agora mesmo" antes do resultado e depois "[confuso]/[pensativo]" — as **marcas de emoção aparecem como texto** na conversa.
6. [LEITURA] O manifesto já tem `queries` MAIN/LAUNCHER, então a visibilidade de pacotes provavelmente não é a causa. Hipóteses a testar: o app está em outro espaço (Segundo Espaço/clone do HyperOS), o rótulo difere, ou a lista de lançáveis vem incompleta. Não confirmado.
7. [DADO] "Anote o nome da minha esposa…": respondeu "Anotado!", "Registro mental feito", depois "corrigido… Thythay…", "Thaynára", "Thaynara". O nome só ficou certo no fim. A esposa aparece como **Thaynara Neves Souza Galvão** (grafia final dada pelo usuário). Antes, "Tainara" e "Thythay" foram erros de escuta/escrita.
8. [LEITURA] **Nada foi realmente gravado na memória.** `MemoryCommands` só salva frases começando com "lembre que / anote que / guarde que / memorize que"; "não eu quero que você Anote aí o nome…" não casa. O Euno **afirmou ter anotado sem anotar**. Depois o usuário: "você esqueceu o nome de Tainara"; a resposta do Euno a essa fala está **vazia** (`Euno: ` sem texto) — turno que não gerou resposta.
9. [DADO] "Quem sou eu para você" → respondeu bem (Joctã, MP + aulas, grafia da Thaynara) — usando o histórico recente.
10. [DADO] "Qual a minha formação" → não sabe; o usuário disse que enviará o dossiê depois (Sobre Mim).
11. [DADO] "Tá me ouvindo" → "Como ainda não tenho a função de ficar escutando o ambiente o tempo todo…" — **o Euno não sabe que agora tem o chamado "Oi Joca"** (SelfKnowledge desatualizado) e disse que "recebe mensagem escrita" ao ser perguntado por escuta de voz.
12. [DADO] "você foi burrinho" → resposta longa e pedindo desculpas; bom tom, mas repetiu o nome com formatação em negrito (Markdown).

## 9. Ferramentas (audit.jsonl, últimas ações)
- [DADO] `agenda_consultar` sucesso (inclusive a falha inicial por falta de permissão em horário anterior), `contatos_buscar` sucesso, `estado_do_celular` sucesso, `lanterna` sucesso, `abrir_app` falhou 2×.
- [DADO] `pesquisar_na_web` falhou com "consulta vazia" (entrada anterior à 0.9.0, que passou a aceitar variações do argumento).
- [DADO] Uma entrada de `estado_do_celular` traz o texto "quando eu começo escrever, não consigo mais ver o texto" (queixa do layout do teclado, anterior à 0.9.0).

## 10. Overlay e desenho (poc01/poc05)
- [DADO] Serviço reiniciado às ~18:30 (`event=start reason=user`, run 5c4735a4); não houve queda (heartbeats a cada 60 s, `uptime` contínuo).
- [DADO] FPS por minuto: 26–30 até as 18:44; **14,3** (18:45), **24,0** (18:46), **0,0** (18:47). [LEITURA] 0 é normal com o personagem recolhido/oculto (tela apagada/portal); o 14,3 e o 22,4 (≈18:41) merecem olhar no vídeo (queda de FPS durante fala/resposta?).
- [DADO] Reação ao toque: 25, 78, 159, 45, 53, 32, 378, 50 ms. O de **378 ms** e o de 159 ms estão acima da meta (<100 ms).
- [DADO] `poc02_touch_session` e `poc04_speech` vazios.

## 11. O que o relatório NÃO mostra (precisa do vídeo ou de outro relatório)
- Se a **legenda** (balão) apareceu, se o app por trás ficou clicável, se "Expandir" funcionou.
- Se o **teclado** agora deixa ver o texto; botão **Nova** e **Resumos de conversa** (nada deles no relatório).
- Se o **bip** do microfone diminuiu.
- Quantas vezes o "Oi Joca" falhou/acionou sem querer.
- O trecho inicial do log (mensagem do Gemini que gerou o descanso).

## 12. Vídeo
- Pendente: ~1 GB, em upload no Google Drive. Quando chegar, acrescentar a seção 13 com o que aparece na tela, em ordem de tempo, ligando cada ponto às linhas acima. Se o arquivo não for legível aqui, avisar em vez de supor.

## 13. Lista inicial de pontos para o usuário confirmar/decidir (sem agir ainda)
1. Voz: o Gemini ficou bloqueado; a sessão toda foi robótica. Causa da cota ainda desconhecida.
2. Memória: o Euno diz que anotou sem anotar; só gravam as frases com prefixos fixos.
3. Cifrões em números (LaTeX) e marcas `[confuso]`/`[pensativo]` visíveis.
4. `abrir_app` não achou WhatsApp/WhatsApp Business.
5. Resposta vazia em um turno ("você esqueceu o nome…").
6. O Euno desconhece o próprio chamado "Oi Joca" (SelfKnowledge).
7. Barge-in (ouvir enquanto fala) ainda não testado (estava desligado).

## 14. Correção do usuário sobre o relatório (anotada a pedido; nada codado)
Palavras do Prof. Joctã: o relatório **não registra tudo nem em tempo real**; com tudo registrado "ele iria encontrar vários e vários erros". Ele precisa de um **identificador de erros completo, log por log**, que pegue **todos** os erros e não alguns. O captador de áudio ("Oi Joca") estava ligado e depois foi desligado por atrapalhar: **na hora que ele falava, o Euno não ouvia; a conversa não ficou fluida; a cada vez era preciso apertar "Falar" de novo porque aparecia a falha nº 11.**

O que isso confirma/contradiz no relatório (fatos do código e do relatório, sem agir):
1. [DADO] O relatório mostra **um único** "código 11" (18:39:25). O usuário relata que apareceu **a cada vez**. Logo, o registro não captou a maioria das falhas.
2. [LEITURA, verificar no código] O ouvinte do chamado (`WakeWordRunner`) cria o `SpeechListener` com `events = null`: **tudo o que ele ouve e todos os seus erros (inclusive o 11) não vão para o registro**. Uma das causas prováveis do "não ouvia": dois reconhecedores disputando o mesmo microfone/serviço (chamado × conversa), o que explicaria falhas como 11 ou "ocupado".
3. [DADO] O relatório inclui só o fim do registro (últimos 60.000 caracteres) e o início aparece cortado ("…(início cortado)…"); a causa da cota do Gemini (HTTP 429) foi justamente perdida por isso.
4. [DADO] O registro só tem o que o código manda escrever (info/warn/error em pontos escolhidos). Não há captura do logcat do Android nem de erros que o app não trata, nem registro de cada evento do reconhecedor (início, fim, erro com código e nome, reinício), nem de cada toque do botão "Falar".
5. [DADO] `poc02_touch_session` e `poc04_speech` ficaram vazios: partes da medição nunca são gravadas.

Requisito anotado para a próxima fase (do usuário): **detector de erros completo**, que registre cada evento e cada falha em ordem, em tempo real, sem perder o começo, incluindo o ouvinte do chamado, os códigos de erro do reconhecedor (com nome), cada reinício, cada toque, e capture exceções e o logcat do próprio app. Não iniciar até o usuário mandar.

## 15. Pedido do usuário: botão "Habilitar tudo para teste completo" (anotado; nada codado)
Palavras do Prof. Joctã: incluir um **botão geral "Habilitar tudo para teste completo"**, para ele não precisar procurar em cada parte dos Ajustes o que ligar, pois isso o faz **errar ou esquecer** algo. Dentro dessa área, **chaves de liga/desliga para cada recurso específico**, cada uma com uma **legenda discreta** dizendo que "é melhor testada em conjunto com tal chave e tal chave" (dica para o desenvolvedor/IA).

Observações para quem for implementar (sugestões, não decisões):
1. Uma área única nos Ajustes (no topo, junto de "Conversa"): botão geral + lista de chaves, cada uma ligada à configuração real (sem estado duplicado).
2. As chaves candidatas hoje (as que mudam o comportamento nos testes): Legenda, Começar ouvindo ao tocar, Conversa contínua, Responder falando, Ouvir comandos enquanto fala, Silenciar o bip, Incluir a conversa no relatório, Chamar pelo nome ("Oi Joca"), e as futuras (registro completo/detector de erros, acessibilidade, etc.). Também as permissões (microfone, agenda, contatos) e a isenção de bateria, que hoje ficam em lugares diferentes.
3. Cada legenda deve citar as chaves "irmãs" reais (ex.: "Ouvir comandos enquanto fala" funciona melhor com "Conversa contínua" e "Responder falando"; "Oi Joca" pode disputar o microfone com a conversa — ver seção 14).
4. O botão geral deve mostrar o que ele ligou e o que precisa de ação do usuário (permissão do Android, bateria "Sem restrições"), em vez de fingir que tudo ficou ligado.
5. Deve existir um jeito de voltar ao estado anterior ("restaurar o que eu tinha").
Não iniciar até o usuário mandar.

## 16. Estado do vídeo "Relatório em Vídeo do Euno" (verificado no Drive)
- [DADO] Encontrado no Drive do usuário: mp4, 947.159.473 bytes (~903 MiB), criado em 08/10/2026 21:50 UTC, último upload em 22:00 UTC. ID 11CbDmvWTh54lr8bZsr2blv2ps5D7m8Al.
- [DADO] As ferramentas de Drive disponíveis só leem texto/documentos (PDF, Office, imagens). Não tocam vídeo, e baixar o arquivo em base64 (~1,26 GB de texto) não cabe no contexto.
- [LEITURA] Por isso não consegui assistir ao vídeo nem extrair seu conteúdo. Nenhum relatório do vídeo foi feito ainda; esta seção não substitui a análise.
- Opções para o usuário destravar: (a) um clipe menor (ex.: 1 a 3 min por parte, ou MP4 com resolução mais baixa); (b) quadros-chave em PNG (um a cada poucos segundos), que eu consigo ler como imagem; (c) a transcrição ou legendas, se o gravador gerar; (d) um texto com o que aparece em cada minuto.
- Nada foi corrigido nem codado.

## 17. Relatório do Gemini sobre o vídeo (documento do Drive, 4 páginas) — conferido contra o relatório de erros
Fonte: Google Doc "Relatório em Vídeo do Euno - Análise Pormenorizada e Audiodescrição" (ID 1RlbonZ1HLuX_R0Qfm54_wBdrwNqHoi9uUyi00VfyRS4). Vídeo: 19 min 01 s; relógio da tela de 18:30 a 18:50; link no YouTube (não listado) citado pelo próprio documento. **Eu não assisti ao vídeo**; abaixo está só o que o Gemini escreveu, confrontado com os dados do relatório de erros.

### 17.1 O que o Gemini descreve (linha do tempo dele, em blocos)
00:00–02:30 usuário reclama de ter de tocar na tela a cada turno; 02:30–05:40 silêncio/retomada; 05:40–07:30 agenda do MPMA (57ª Promotoria; UEBs) e hora 18:35; 07:30–08:00 vôlei; 08:00–09:15 "o que sabe da esposa"; 09:15–09:50 contato "meu amor ❤️" com (98) 98717-7598; 09:50–11:05 WhatsApp e WhatsApp Business falham, chips "Confuso"/"Pensativo"; 11:05–13:55 nome da esposa e apelido "Tita"; 13:55–16:15 correções de grafia, "burrice digital"; 16:15–17:45 "quem sou eu para você"; 17:45–19:01 formação e despedida.

### 17.2 O que confere com o relatório de erros (alta confiança)
- Agenda, hora 18:35, contato "Meu Amor ❤️", falha ao abrir WhatsApp/Business, correções do nome (Thaynara), "burrice", "quem sou eu", formação desconhecida, despedida: todos aparecem também na conversa do relatório.
- O usuário tem de tocar/falar repetidamente a cada turno (coerente com o erro 11 e a queixa do usuário, seção 14).

### 17.3 O que NÃO confere ou é suspeito (não usar como fato)
1. **"Memória persistente: Sucesso Total"** — contradiz o código: nada foi gravado, porque "anote o nome…" não casa com os prefixos de `MemoryCommands` (seção 8, item 8). O Gemini avaliou pela fala do Euno ("anotado"), não por prova de gravação.
2. **Apelido "Tita"** — não existe na conversa do relatório; pode ser algo dito só no vídeo, ou invenção do Gemini. Confirmar.
3. **"Botão circular azul/roxo com 4 estados (Standby/Listening/Thinking/Speaking)", "tema #121212", "waveform"** — não corresponde ao que o código desenha (cartão de chat com "Ajustes/Fechar/Nova", botão "Falar"/"Enviar", personagem). Descrição provavelmente inventada. Não confiar na seção 2 do documento.
4. **Pacotes `com.whatsapp` e `com.whatsapp.w4b`** — o registro de ferramentas só diz "não encontrei um app chamado …". Os nomes de pacote são suposição dele.
5. **Bloco 1: "Euno explica tecnicamente a versão 0.10.0, sensibilidade a ruídos e promete escuta contínua nas próximas builds"** — o texto do relatório de erros não traz essa fala do Euno. Se ocorreu, foi antes das 18:34 ou fora do trecho; se não, é invenção. Verificar no vídeo.
6. **"Escuta contínua: parcial por ruído ambiental / quedas de conexão"** — diagnóstico do Gemini sem evidência. O dado real é o erro 11 (SERVER_DISCONNECTED, a confirmar) e a hipótese de dois reconhecedores disputando o microfone (seção 14).
7. **Recomendação de "bipe de confirmação sonoro"** — o usuário quer o contrário (menos bip). Descartar.
8. O documento **não menciona**: voz robótica do Android (cota do Gemini), legenda, botão "Nova", "Oi Joca" (que no relatório de erros foi reconhecido às 18:42), teclado, nem a falha 11 explícita. Ou não apareceram no vídeo, ou o Gemini não percebeu. Só o vídeo (quadros) resolve.

### 17.4 Conclusão provisória
O relatório do Gemini serve como roteiro de tempo e confirma o que o relatório de erros já mostrava, mas **mistura fatos com invenções** (itens 1, 3, 4, 6). Para decidir correções, usar: (a) o relatório de erros, (b) o que o usuário disse, (c) quadros/prints do vídeo quando chegarem. Nada foi corrigido nem codado.
