# ADR 0005 — Voz na Fase 0: reconhecedor e TTS do próprio Android como linha de base

**Status:** aceita (Fase 0) · **Data:** 2026-10-06

O roteiro escolheu sherpa-onnx (STT/TTS offline). Integrá-lo exige baixar modelos de centenas de MB e escolher modelos PT-BR — trabalho que só vale a pena depois de saber o quanto o que já vem no aparelho resolve.

**Decisão:** a PoC 0.4 mede primeiro o `SpeechRecognizer` do Android (preferindo o reconhecedor **no aparelho**, `createOnDeviceSpeechRecognizer`, API 31+) e o `TextToSpeech` do sistema, com WER automático em 20 frases fixas.

**Próximo passo (PoC 0.4b):** integrar sherpa-onnx (`com.k2fsa.sherpa.onnx:sherpa-onnx-android`, Maven Central) e comparar nas mesmas 20 frases. Se o reconhecedor do sistema já for bom offline, o sherpa-onnx pode ficar para o MVP 2.
