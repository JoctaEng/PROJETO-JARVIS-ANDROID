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
