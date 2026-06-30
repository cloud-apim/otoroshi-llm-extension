# Rampart PII model (bundled)

These files are the **Rampart** client-side PII detection model, bundled here so the
`rampart` guardrail, the `rampart_redact` workflow function and the Rampart raw-body
plugins can run the model **locally, in-process, with no external network call**.

## Provenance

- **Model:** Rampart
- **Author / publisher:** National Design Studio
- **Source:** https://huggingface.co/nationaldesignstudio/rampart
- **License:** Creative Commons Attribution 4.0 International (**CC BY 4.0**) — see the
  `LICENSE` file in this folder.
- **Base model:** [`nreimers/MiniLM-L6-H384-uncased`](https://huggingface.co/nreimers/MiniLM-L6-H384-uncased)
- **Training data:** [`ai4privacy/pii-masking-openpii-1.5m`](https://huggingface.co/datasets/ai4privacy/pii-masking-openpii-1.5m)
  (also CC BY 4.0)

The files in this folder are **redistributed unmodified** from the source repository.

## Files

| File | Purpose |
|---|---|
| `model_q4.onnx` | The token-classification model, ONNX, 4-bit quantized (`MatMulNBits`), ~14.7 MB |
| `tokenizer.json` | WordPiece tokenizer (loaded via DJL `HuggingFaceTokenizer`) |
| `config.json` | Model config, incl. the 35-label BIO `id2label` mapping |
| `special_tokens_map.json`, `tokenizer_config.json` | Tokenizer metadata |
| `LICENSE` | The upstream CC BY 4.0 license text |

## Technical summary

- `BertForTokenClassification` (MiniLM: 6 layers, hidden 384), 35-label BIO head,
  max sequence length 512 tokens.
- Detects 17 PII entity types; the runtime keeps coarse geo markers (CITY / STATE / ZIP_CODE)
  un-redacted by default.
- Best on Latin-script languages (EN, ES, FR, DE, IT, PT, NL). Recall on non-Latin scripts
  is significantly lower — see the upstream model card.

## How it is loaded

Loaded once and cached by `com.cloud.apim.otoroshi.extensions.aigateway.guardrails.RampartEngine`
via `env.environment.resourceAsStream("cloudapim/extensions/ai/models/rampart/...")`.
The neural model is paired with a deterministic recognizer layer (EMAIL / URL / IP / SSN /
CREDIT_CARD with Luhn) for high-precision structured identifiers.

## Attribution (CC BY 4.0)

> "Rampart" by National Design Studio (https://huggingface.co/nationaldesignstudio/rampart),
> licensed under CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). Redistributed unmodified.

This attribution is also recorded in the project-level `NOTICE` file.
