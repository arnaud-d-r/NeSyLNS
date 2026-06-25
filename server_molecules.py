import torch
from flask import Flask, request
import traceback
from transformers import AutoTokenizer, AutoModelForMaskedLM, GPT2LMHeadModel, GPT2TokenizerFast

# Load ChemBERTa model
print("Loading ChemBERTa model...")
device = 'cuda' if torch.cuda.is_available() else 'cpu'
print(f"Using device: {device}")

tokenizer = AutoTokenizer.from_pretrained("seyonec/ChemBERTa-zinc-base-v1")
model = AutoModelForMaskedLM.from_pretrained("seyonec/ChemBERTa-zinc-base-v1").to(device)
print("Model loaded successfully")


ppl_tokenizer = GPT2TokenizerFast.from_pretrained("entropy/gpt2_zinc_87m", max_len=40)
ppl_model = GPT2LMHeadModel.from_pretrained('entropy/gpt2_zinc_87m').to(device)

mask_string = "<mask>"

app = Flask(__name__)

def tokenize(mol_string: str) -> 'list[str]':
    if len(mol_string) == 0:
        return []
    elif len(mol_string) == 1:
        return [mol_string]
    skip = 0
    n = len(mol_string)
    mol_array = []
    for i in range(n):
        if skip > 0:
            skip -= 1
            continue
        token = mol_string[i]
        if token == '%':
            skip = 2
            mol_array.append(mol_string[i:i+3])
        elif token == '<':
            if mol_string[i+1] == '/':
                skip = 3
                mol_array.append(mol_string[i:i+4])
            else:
                skip = 2
                mol_array.append(mol_string[i:i+3])
        elif i != n - 1 and token == 'C' and mol_string[i+1] == 'l':
            skip = 1
            mol_array.append('Cl')
        elif i != n - 1 and token == 'B' and mol_string[i+1] == 'r':
            skip = 1
            mol_array.append('Br')
        elif i != n - 1 and token == 'H' and mol_string[i+1] == '3':
            skip = 1
            mol_array.append('H3')
        else:
            mol_array.append(token)
            
    return mol_array

TOKENS = {
    'F',
    'Cl',
    'Br',
    'I',
    'O',
    'N',
    'S',
    'C',
    '[',
    ']',
    '-',
    '+',
    '@',
    'H',
    'H3',
    '1',
    '2',
    '3',
    '4',
    '5',
    '6',
    '7',
    '8',
    '=',
    '/',
    '\\',
    '(',
    ')',
    '#',
    'o',
    'n',
    's',
    'c',
}


def get_mask_distributions(sentence):
    """Get probability distributions for masked positions in the sentence."""
    inputs = tokenizer(sentence, return_tensors="pt").to(device)
    with torch.no_grad():
        outputs = model(**inputs)
        logits = outputs.logits

    mask_token_id = tokenizer.mask_token_id
    mask_positions = (inputs.input_ids == mask_token_id).nonzero(as_tuple=True)[1].tolist()
    mask_positions.sort()


    distributions = {}
    for idx_in_mask_positions, pos in enumerate(mask_positions):
        # Get probabilities for all tokens at this position
        bert_probabilities = torch.softmax(logits[0, pos], dim=-1).cpu().tolist()
        bert_tokens = {tokenizer.decode([idx]).strip(): bert_probabilities[idx] 
                      for idx in range(len(bert_probabilities))}
        
        # Aggregate probabilities for tokens in TOKENS
        probs = {token: 0 for token in TOKENS}
        for bert_token_str, prob in bert_tokens.items():
            split_ngram = tokenize(bert_token_str)
            if len(split_ngram) == 0:  # Skip empty strings
                continue
            t = split_ngram[0]  # Get the first token from the ngram
            if t not in TOKENS:  # Skip if the token is not in our grammar
                continue
            probs[t] += prob
        
        # Normalize probabilities
        summed_values = sum(probs.values())
        if summed_values > 0:  # Avoid division by zero
            probs = {k: v/summed_values for k, v in probs.items()}

        
        distributions[int(pos)-1] = {
            "mask_index": int(pos)-1,
            "tokens": list(probs.keys()),
            "probs": list(probs.values())
        }

    return distributions

@app.route('/ping', methods=['GET'])
def ping():
    return 'pong', 200

@app.route('/mlm', methods=['POST'])
def mlm_predict():
    """Predict masked tokens in a molecule SMILES string."""
    try:
        sentence = request.data.decode()
        sentence = sentence.replace("*", mask_string)

        if mask_string not in sentence:
            return {"error": f"Sentence must contain a mask token ({mask_string})"}, 400

        distributions = get_mask_distributions(sentence)
        return distributions, 200
    except Exception as e:
        traceback.print_exc()
        return {"error": str(e)}, 500

@app.route('/mlm_tokenize', methods=['POST'])
def mlm_tokenize():
    """Tokenize a molecule SMILES string."""
    try:
        sentence = request.data.decode()
        tokens = tokenizer.tokenize(sentence)
        token_ids = tokenizer.convert_tokens_to_ids(tokens)
        return {"tokens": tokens, "token_ids": token_ids}, 200
    except Exception as e:
        traceback.print_exc()
        return {"error": str(e)}, 500

@app.route('/mlm_perplexity', methods=['POST'])
def mlm_perplexity():
    """Calculate pseudo-perplexity for a molecule SMILES string."""
    try:
        sentence = request.data.decode()

        # Tokenize
        encoded = tokenizer(sentence, return_tensors="pt")
        input_ids = encoded["input_ids"].to(device)
        attention_mask = encoded["attention_mask"].to(device)

        tokens = tokenizer.convert_ids_to_tokens(input_ids[0].tolist())
        special_ids = set(tokenizer.all_special_ids)
        mask_token_id = tokenizer.mask_token_id

        token_probs = []

        with torch.no_grad():
            seq_len = input_ids.shape[1]
            for pos in range(seq_len):
                orig_id = int(input_ids[0, pos].item())
                if orig_id in special_ids:
                    continue

                # Mask this position
                masked_ids = input_ids.clone()
                masked_ids[0, pos] = mask_token_id

                # Get prediction
                outputs = model(input_ids=masked_ids, attention_mask=attention_mask)
                logits = outputs.logits[0, pos]
                prob = torch.softmax(logits, dim=-1)[orig_id].item()

                token_probs.append({
                    "index": pos,
                    "token": tokens[pos],
                    "prob": prob
                })

        return {"tokens": tokens, "token_probs": token_probs}, 200
    except Exception as e:
        traceback.print_exc()
        return {"error": str(e)}, 500
    

def calculate_perplexity(sentence: str) -> float:
    encodings = ppl_tokenizer(sentence, return_tensors="pt").to(device)
    with torch.no_grad():
        outputs = ppl_model(**encodings, labels=encodings.input_ids)
        loss = outputs.loss
    return torch.exp(loss).item()




@app.route('/perplexity', methods=['POST'])
def perplexity():
    sentence = request.data.decode()
    return {"perplexity": calculate_perplexity(sentence)}

if __name__ == '__main__':
    print("Starting Flask server...")
    app.run(host="0.0.0.0", port=5001, threaded=True)