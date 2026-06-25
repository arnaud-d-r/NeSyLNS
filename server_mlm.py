import os
import sys
import time
import json
import traceback
import argparse
from threading import Lock

sys.stdout.reconfigure(line_buffering=True)
sys.stderr.reconfigure(line_buffering=True)

# ── CLI args (parsed first so --offline affects env vars before imports) ──────

parser = argparse.ArgumentParser(description="Flask server for MLM token prediction")
parser.add_argument('--port',    type=int,  default=5000,
                    help='Port to run the server on')
# To use a different model, pass its Hugging Face model ID here.
# Example: python server.py --model "roberta-base"
parser.add_argument('--model',   type=str,  default="answerdotai/ModernBERT-base",
                    help='Hugging Face model ID (must be a masked LM)')
# Use --offline to prevent any network calls (useful on air-gapped machines).
parser.add_argument('--offline', action='store_true',
                    help='Run in offline mode (no Hugging Face hub network calls)')
args = parser.parse_args()

if args.offline:
    os.environ['TRANSFORMERS_OFFLINE'] = '1'
    os.environ['HF_HUB_OFFLINE']       = '1'
os.environ['HF_HUB_DISABLE_TELEMETRY'] = '1'

print("Importing dependencies...")
try:
    t = time.time()
    import torch
    print(f"torch loaded in {time.time()-t:.1f}s")

    t = time.time()
    from transformers import AutoModelForMaskedLM, AutoTokenizer
    print(f"transformers loaded in {time.time()-t:.1f}s")

    from flask import Flask, request
    print("All imports done.")
except Exception:
    traceback.print_exc(file=sys.stderr)
    sys.exit(1)

# ── Device & model loading ────────────────────────────────────────────────────

device = 'cuda' if torch.cuda.is_available() else 'cpu'
print(f"Using device: {device}")
if device == 'cuda':
    torch.cuda.set_device(0)

try:
    print(f"Loading tokenizer: {args.model}")
    tokenizer = AutoTokenizer.from_pretrained(args.model)

    # The mask token varies by model family:
    #   [MASK]  — BERT, ModernBERT, DistilBERT
    #   <mask>  — RoBERTa, DeBERTa, ELECTRA, ChemBERTa
    # tokenizer.mask_token always gives the correct string — no manual editing needed.
    MASK_TOKEN    = tokenizer.mask_token
    MASK_TOKEN_ID = tokenizer.mask_token_id
    print(f"Mask token for this model: '{MASK_TOKEN}' (id={MASK_TOKEN_ID})")

    print(f"Loading model: {args.model}")
    t     = time.time()
    model = AutoModelForMaskedLM.from_pretrained(args.model, torch_dtype=torch.bfloat16).to(device)
    model.eval()
    print(f"Model ready in {time.time()-t:.1f}s")
except Exception:
    traceback.print_exc(file=sys.stderr)
    sys.exit(1)

# ── Flask app ─────────────────────────────────────────────────────────────────

app   = Flask(__name__)
mutex = Lock()

# ── Helpers ───────────────────────────────────────────────────────────────────

def get_mask_distributions(sentence: str) -> dict:
    """Return softmax probability distributions for each mask position in the sentence.

    The sentence should contain one or more occurrences of the model's mask token
    (available via the /mask_str endpoint). The function detects mask positions
    automatically, so it works regardless of the model family.
    """
    inputs = tokenizer(sentence, return_tensors="pt").to(device)
    with torch.no_grad():
        logits = model(**inputs).logits

    mask_positions = (inputs.input_ids == MASK_TOKEN_ID).nonzero(as_tuple=True)[1].tolist()
    mask_positions.sort()

    # Map each mask token position back to its word index in the original sentence
    words = sentence.split()
    mask_word_positions = [i for i, w in enumerate(words) if w == MASK_TOKEN]

    if len(mask_positions) != len(mask_word_positions):
        print(f"[warn] mask count mismatch — "
              f"token-level={len(mask_positions)}, word-level={len(mask_word_positions)}")

    distributions = {}
    for rank, pos in enumerate(mask_positions):
        probs    = torch.softmax(logits[0, pos], dim=-1).cpu().tolist()
        word_pos = mask_word_positions[rank] if rank < len(mask_word_positions) else None
        distributions[int(pos)] = {
            "mask_index":         int(pos),
            "mask_word_position": word_pos,
            "tokens":             list(range(len(probs))),
            "probs":              probs,
        }
    return distributions

# ── Routes ────────────────────────────────────────────────────────────────────
@app.route('/ping', methods=['GET'])
def ping():
    return 'pong', 200

@app.route('/mask_str', methods=['GET'])
def get_mask_string():
    """Return the mask token string for the loaded model.
    Clients should use this to build masked sentences instead of hardcoding [MASK] or <mask>.
    """
    return {'mask_string': MASK_TOKEN}, 200


@app.route('/mlm', methods=['POST'])
def mlm_predict():
    """Return per-mask token probability distributions.

    POST body (JSON):
        { "sentence": "The cat sat on the [MASK]." }

    The mask token must match the model's own mask token (use /mask_str to retrieve it).
    """
    try:
        data     = request.get_json()
        sentence = data["sentence"]

        if MASK_TOKEN not in sentence:
            return {"error": f"Sentence must contain the mask token: '{MASK_TOKEN}'"}, 400

        with mutex:
            distributions = get_mask_distributions(sentence)
        return distributions, 200
    except Exception as e:
        traceback.print_exc()
        return {"error": str(e)}, 500


@app.route('/mlm_tokenize', methods=['POST'])
def mlm_tokenize():
    """Tokenize raw text and return token strings and their IDs.

    POST body: raw text (not JSON).
    """
    try:
        sentence  = request.data.decode()
        tokens    = tokenizer.tokenize(sentence)
        token_ids = tokenizer.convert_tokens_to_ids(tokens)
        return {"tokens": tokens, "token_ids": token_ids}, 200
    except Exception as e:
        traceback.print_exc()
        return {"error": str(e)}, 500


@app.route('/mlm_perplexity', methods=['POST'])
def mlm_perplexity():
    """Compute pseudo-log-likelihood scores via masked token probabilities.

    Returns per-token probabilities and per-word geometric mean probabilities.
    POST body: raw text (not JSON).
    """
    try:
        sentence = request.data.decode()

        encoded        = tokenizer(sentence, return_tensors="pt", return_offsets_mapping=True)
        input_ids      = encoded["input_ids"].to(device)
        attention_mask = encoded["attention_mask"].to(device)

        tokens      = tokenizer.convert_ids_to_tokens(input_ids[0].tolist())
        special_ids = set(tokenizer.all_special_ids)

        enc0     = encoded.encodings[0] if getattr(encoded, "encodings", None) else None
        word_ids = enc0.word_ids if enc0 else [None] * input_ids.shape[1]
        offsets  = enc0.offsets  if enc0 else [(0, 0)] * input_ids.shape[1]

        seq_len               = input_ids.shape[1]
        non_special_positions = [i for i in range(seq_len) if int(input_ids[0, i]) not in special_ids]

        # Batch-mask: replace one token at a time and collect logits
        BATCH_SIZE         = 16
        position_to_logits = {}

        for i in range(0, len(non_special_positions), BATCH_SIZE):
            batch_pos  = non_special_positions[i:i + BATCH_SIZE]
            batch_ids  = input_ids.repeat(len(batch_pos), 1)
            batch_attn = attention_mask.repeat(len(batch_pos), 1)
            for j, pos in enumerate(batch_pos):
                batch_ids[j, pos] = MASK_TOKEN_ID

            with torch.no_grad():
                logits = model(input_ids=batch_ids, attention_mask=batch_attn).logits
            for j, pos in enumerate(batch_pos):
                position_to_logits[pos] = logits[j, pos].cpu()

        # Per-token probabilities
        token_probs   = []
        word_products = {}
        word_counts   = {}

        for pos in range(seq_len):
            orig_id = int(input_ids[0, pos])
            if orig_id in special_ids:
                continue
            prob = torch.softmax(position_to_logits[pos], dim=-1)[orig_id].item()
            wid  = word_ids[pos]
            token_probs.append({"index": pos, "token": tokens[pos], "prob": prob, "word_id": wid})
            if wid is not None:
                word_products[wid] = word_products.get(wid, 1.0) * max(prob, 1e-12)
                word_counts[wid]   = word_counts.get(wid, 0) + 1

        # Recover character spans for each word
        word_spans = {}
        for pos, wid in enumerate(word_ids):
            if wid is None:
                continue
            s, e = offsets[pos]
            if wid not in word_spans:
                word_spans[wid] = [s, e]
            else:
                word_spans[wid][0] = min(word_spans[wid][0], s)
                word_spans[wid][1] = max(word_spans[wid][1], e)

        # Per-word geometric mean probability
        word_probs = []
        for wid in sorted(word_products):
            s, e  = word_spans.get(wid, (0, 0))
            count = word_counts[wid]
            word_probs.append({
                "word_id": wid,
                "word":    sentence[s:e] if e > s else "",
                "prob":    word_products[wid] ** (1.0 / count),
            })

        return {"tokens": tokens, "token_probs": token_probs, "word_probs": word_probs}, 200
    except Exception as e:
        traceback.print_exc()
        return {"error": str(e)}, 500


if __name__ == '__main__':
    print(f"Starting server on port {args.port}...")
    try:
        app.run(host="0.0.0.0", port=args.port, threaded=True)
    except Exception:
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)