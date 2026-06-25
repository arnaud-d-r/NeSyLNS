from transformers import AutoModelForMaskedLM, AutoModelForCausalLM, AutoTokenizer
import torch
import json
import argparse
from lemminflect import getAllInflections
from pathlib import Path

# ── CLI ───────────────────────────────────────────────────────────────────────

parser = argparse.ArgumentParser(
    description="Build a symbolic vocabulary corpus from a lemma set."
)

# To use a different model, pass its Hugging Face model ID here.
# Example: python script.py --model "roberta-base" --model-type masked
parser.add_argument(
    "--model",
    type=str,
    default="stabilityai/stablelm-zephyr-3b",
    help="Hugging Face model ID to use for tokenization."
)
# Set to 'masked' for BERT-style MLM models, 'causal' for GPT-style CLM models.
parser.add_argument(
    "--model-type",
    type=str,
    choices=["masked", "causal"],
    default="causal",
    help="Type of model architecture: 'masked' (BERT-style) or 'causal' (GPT-style)."
)
args = parser.parse_args()



# ── Configuration ─────────────────────────────────────────────────────────────

BASE_DIR = Path(__file__).parent
device = 'cuda' if torch.cuda.is_available() else 'cpu'

model_dir_name = args.model.replace("/", "_")
output_dir = BASE_DIR / model_dir_name
output_dir.mkdir(exist_ok=True)

# ── Load model & tokenizer ────────────────────────────────────────────────────

print(f"Loading tokenizer: {args.model}")
tokenizer = AutoTokenizer.from_pretrained(args.model)

# The model itself is only needed for the mask-prediction sanity check (Part 2).
# If you only need the corpus files (Part 1), you can skip loading the model.
print(f"Loading model: {args.model} ({args.model_type})")
if args.model_type == "masked":
    model = AutoModelForMaskedLM.from_pretrained(args.model).to(device)
else:
    model = AutoModelForCausalLM.from_pretrained(args.model, device_map="auto")

# ── Part 1: Corpus generation from lemma set ──────────────────────────────────

# Point this to your own lemma set JSON file if you want to use a different vocabulary domain.
json_path = BASE_DIR / "ENGLISH_LEMSET_CORRECTED+math.json"

with open(json_path, 'r', encoding="UTF-8") as file:
    lemset = json.load(file)

print(f"Lemmas loaded: {len(lemset)}")

# Collect all words and their inflected forms
words = list(lemset)
for word in lemset:
    inflections = getAllInflections(word)
    for forms in inflections.values():
        words.extend(forms)
words = list(set(words))

print(f"Words after inflection expansion: {len(words)}")

# Tokenize each word (and its capitalized variant) to get token IDs
tokens = []
tokenized_words = []
for word in words:
    for variant in (f" {word}", f" {word.capitalize()}"):
        token_ids = tokenizer.convert_tokens_to_ids(tokenizer.tokenize(variant))
        tokens.extend(token_ids)
        tokenized_words.append(token_ids)
tokens = list(set(tokens))

print(f"Unique tokens: {len(tokens)}")

# Save corpus files — these are the files consumed by the symbolic filtering method
(output_dir / "corpus_domain.json").write_text(json.dumps(tokens), encoding="UTF-8")
(output_dir / "corpus_words.json").write_text(json.dumps(words), encoding="UTF-8")
(output_dir / "corpus_tokenized_words.json").write_text(json.dumps(tokenized_words), encoding="UTF-8")

print("Corpus saved.")

# ── Part 2: Tokenizer vocabulary export ───────────────────────────────────────

# This section exports the full token-index mapping of the model's tokenizer.
# It lets you inspect which token ID corresponds to which string — useful for
# debugging or adapting the symbolic filter to a new model's vocabulary.

if args.model_type == "masked":
    # Run a masked prediction to confirm the model and tokenizer are working
    if tokenizer.mask_token is None:
        print("Warning: this model has no mask token — skipping sanity check.")
    else:
        sample = f"c{tokenizer.mask_token}"
        inputs = tokenizer(sample, return_tensors="pt").to(device)
        with torch.no_grad():
            predictions = model(**inputs).logits

        mask_position = (
            inputs.input_ids == tokenizer.mask_token_id
        )[0].nonzero(as_tuple=True)[0].tolist()[0]

        vocab_size = predictions.shape[-1]
        print(f"MLM vocabulary size: {vocab_size}")
else:
    vocab_size = tokenizer.vocab_size
    print(f"Tokenizer vocabulary size: {vocab_size}")

# Decode every token index to its string representation
decoded_tokens = [
    tokenizer.decode([idx], skip_special_tokens=False)
    for idx in range(vocab_size)
]

output_path = output_dir / "tokenizer_dict.txt"
with open(output_path, 'w', encoding="UTF-8") as f:
    for idx, token in enumerate(decoded_tokens):
        try:
            f.write(f"{idx}::{token}\n")
        except Exception:
            print(f"Could not write token: {token}")

print(f"Tokenizer dictionary saved: {output_path}")