# Revisão geral do "corpo" do Euno (pedido 70, 10/10/2026)

Base: código da 0.25.1, relatório da 0.24.0 e conversa exportada (09–10/10). Só JVM testado aqui; o app compila no CI.

## Inventário (o que ele sabe usar)
- **Apps e celular**: abrir_app, listar_apps, estado_do_celular, criar_alarme, criar_timer, lanterna, compartilhar_texto, abrir_link, pesquisar_na_web, abrir_mapa.
- **Agenda e contatos**: agenda_consultar, agenda_criar (toca em Salvar/OK sozinho), contatos_buscar, whatsapp_mensagem, resumo_do_dia.
- **Memória**: memoria_guardar, memoria_esquecer, memoria_buscar.
- **Tela** (com o controle do celular ligado): tela_ler, tela_ver (print + Gemini), tela_tocar, tela_tocar_ponto, tela_digitar, tela_rolar (com procurar), tela_deslizar, tela_navegar.
- **Apps parceiros (MCP)**: EduMath e servidores de rede cadastrados.
- **Comandos sem cérebro**: tchau, parar, pera aí, parar de ouvir, "lembre que…", "esqueça…"; chamado "Oi Joca"/"Oi <avatar>".

## Inconsistências achadas e corrigidas na 0.25.2
| # | Problema | Correção |
|---|---|---|
| 1 | Autodescrição dizia "e-mail ainda não existe; diga que está no roteiro" → recusava **abrir o Gmail** | Texto separa "ler por dentro" de "abrir o app"; nova linha "você abre QUALQUER app e age na tela dele" |
| 2 | Turno sem ferramentas dizia "nenhuma disponível; só conversa" → "não consigo" em "e agora?", "continua" | Cérebro online recebe as ferramentas **sempre**; sem ferramentas, ele pede para repetir em vez de dizer que é incapaz |
| 3 | Regra "o que não estiver lá, chega nas próximas versões" + histórico com recusas antigas | Regra: antes de recusar, combinar ferramentas; recusa anterior pode ter sido erro |
| 4 | "[usando agenda_consultar]" escrito sem chamar; "voltei para a tela inicial" sem ferramenta | `HonestyCheck` + `AgentRunner.verifyClaims`: corrige o cérebro uma vez antes de mostrar/falar |
| 5 | agenda_consultar sem dia específico; "15/10" virava **hoje** e "17/10" virava **semana** (por ter "7") | `AgendaRequest`: data (15/10, 2026-10-15, dia 15), mês, N dias; texto sem sentido dá erro |
| 6 | Cérebro que volta vazio com ferramentas era tentado de novo em todo pedido | Fica por último em pedidos com ação (aprende na sessão) |
| 7 | Aviso "bateria baixa ou aparelho quente" em todo turno (37×) com bateria a 99% | Só avisa se existir cérebro do celular a evitar; código térmico desconhecido não conta como quente |
| 8 | Confirmação (ex.: "Enviar") abria o chat por cima do app → o toque confirmado caía na tela do Euno | Com o personagem na tela, a confirmação aparece no balão |
| 9 | Ferramentas de tela no prompt mesmo com o controle desligado | Só entram com o controle ligado (prompt menor, sem erro à toa) |
| 10 | Leitura que só via o Euno era entendida como "o app fechou / estou na tela inicial" | Mensagem diz que isso não prova nada e indica tela_ver / tela_navegar |
| 11 | tela_navegar não esperava a tela mudar | Espera assentar |
| 12 | WhatsApp: "Maria" com "Maria" e "Maria Souza" → desistia | Nome exato ganha |
| 13 | Autodescrição dizia "Você é o Euno 0.24" e a persona "Você é a Clara" | "Você é a Clara, o personagem do app Euno (versão …)" |
| 14 | "Clara, pode ir" / "Guardião, para" não eram comandos (nomes dos avatares não eram palavras de apoio) | Todos os nomes de personagens e avatares entram |
| 15 | Mensagem de política falava em "JARVIS" | Fala de "Meu Euno" |

Custo conhecido: com ferramentas sempre no prompt, o texto da resposta aparece de uma vez (não em streaming) e o Groq grátis bate o limite por minuto com mais frequência (o Gemini responde).
