#!/usr/bin/env python3
"""
Script to update perplexity scores in JSON files by querying an LLM.
Reads all JSON files in the current directory and recalculates perplexities.
Also builds best_perplexity_evolution history from logs.
Uses batching for faster processing.
"""

import json
import os
import glob
from pathlib import Path
from typing import Dict, List, Tuple
import torch
from transformers import AutoTokenizer, AutoModelForCausalLM
from tqdm import tqdm
import numpy as np


class PerplexityCalculator:
    """Calculate perplexity using a language model with batching support."""
    
    def __init__(self, model_name: str = "meta-llama/Llama-3.2-3B", batch_size: int = 8):
        """
        Initialize the perplexity calculator.
        
        Args:
            model_name: HuggingFace model identifier
            batch_size: Number of sentences to process at once
        """
        print(f"Loading model: {model_name}")
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        print(f"Using device: {self.device}")
        
        self.tokenizer = AutoTokenizer.from_pretrained(model_name)
        # Set padding token if not set
        if self.tokenizer.pad_token is None:
            self.tokenizer.pad_token = self.tokenizer.eos_token
            
        self.model = AutoModelForCausalLM.from_pretrained(
            model_name,
            torch_dtype=torch.float16 if self.device == "cuda" else torch.float32,
            device_map="auto" if self.device == "cuda" else None
        )
        self.model.eval()
        
        if self.device == "cpu":
            self.model = self.model.to(self.device)
        
        self.batch_size = batch_size
        print(f"Batch size: {batch_size}")
    
    def calculate_perplexity(self, sentence: str) -> float:
        """
        Calculate perplexity for a single sentence.
        
        Args:
            sentence: Input sentence
            
        Returns:
            Perplexity score
        """
        results = self.calculate_perplexity_batch([sentence])
        return results[0]
    
    def calculate_perplexity_batch(self, sentences: List[str]) -> List[float]:
        """
        Calculate perplexity for a batch of sentences.
        
        Args:
            sentences: List of input sentences
            
        Returns:
            List of perplexity scores
        """
        if not sentences:
            return []
        
        # Tokenize all sentences with padding
        encodings = self.tokenizer(
            sentences,
            return_tensors="pt",
            padding=True,
            truncation=True,
            max_length=512
        )
        
        input_ids = encodings.input_ids.to(self.device)
        attention_mask = encodings.attention_mask.to(self.device)
        
        perplexities = []
        
        with torch.no_grad():
            # Process each sentence to get individual perplexities
            for i in range(len(sentences)):
                # Get the actual tokens (excluding padding)
                seq_len = attention_mask[i].sum().item()
                sentence_input_ids = input_ids[i:i+1, :seq_len]
                
                # Calculate loss for this sentence
                outputs = self.model(sentence_input_ids, labels=sentence_input_ids)
                loss = outputs.loss
                perplexity = torch.exp(loss).item()
                perplexities.append(perplexity)
        
        return perplexities
    
    def calculate_perplexities_for_list(self, sentences: List[str]) -> List[float]:
        """
        Calculate perplexities for a list of sentences using batching.
        
        Args:
            sentences: List of input sentences
            
        Returns:
            List of perplexity scores
        """
        all_perplexities = []
        
        # Process in batches
        for i in range(0, len(sentences), self.batch_size):
            batch = sentences[i:i + self.batch_size]
            batch_perplexities = self.calculate_perplexity_batch(batch)
            all_perplexities.extend(batch_perplexities)
        
        return all_perplexities


def build_best_perplexity_evolution(logs: List[Dict]) -> List[Dict]:
    """
    Build best_perplexity_evolution from logs by tracking improvements.
    
    Args:
        logs: List of log entries with sentence, perplexity, and timestamp
        
    Returns:
        List of best perplexity evolution entries
    """
    if not logs:
        return []
    
    evolution = []
    best_perplexity = float('inf')
    
    for i, log_entry in enumerate(logs):
        current_perplexity = log_entry.get('perplexity', -1) or log_entry.get('score', -1)
        
        
        # Check if this is a new best
        if current_perplexity < best_perplexity:
            best_perplexity = current_perplexity
            
            evolution_entry = {
                'sentence': log_entry.get('sentence', '') or log_entry.get('molecule', ''),
                'score': current_perplexity,
                'time': log_entry.get('timestamp', i * 1000),
            }
            evolution.append(evolution_entry)

    
    return evolution





def update_json_file(filepath: Path, calculator: PerplexityCalculator, 
                     dry_run: bool = False, rebuild_evolution: bool = True) -> Dict:
    """
    Update perplexity scores in a JSON file using batched processing.
    
    Args:
        filepath: Path to JSON file
        calculator: PerplexityCalculator instance
        dry_run: If True, don't write changes to file
        rebuild_evolution: If True, rebuild best_perplexity_evolution from logs
        
    Returns:
        Updated JSON data
    """
    print(f"\nProcessing: {filepath}")
    
    with open(filepath, 'r') as f:
        data = json.load(f)
    
    # Update base sentence perplexity
    if 'base_sentence' in data and 'sentence' in data['base_sentence']:
        sentence = data['base_sentence']['sentence']
        print(f"Calculating perplexity for base sentence: {sentence}")
        perplexity = calculator.calculate_perplexity(sentence)
        data['base_sentence']['perplexity'] = perplexity
        print(f"  Base perplexity: {perplexity:.4f}")
    elif 'base_sentence' in data and 'molecule' in data['base_sentence']:
        sentence = data['base_sentence']['molecule']
        print(f"Calculating perplexity for base molecule: {sentence}")
        perplexity = calculator.calculate_perplexity(sentence)
        data['base_sentence']['score'] = perplexity
        print(f"  Base perplexity: {perplexity:.4f}")
    
    # Update logs perplexities using batching
    if 'logs' in data:
        print(f"Updating {len(data['logs'])} log entries (batched)...")
        
        # Extract all sentences
        sentences = [log['sentence'] if 'sentence' in log else log['molecule'] for log in data['logs'] if 'molecule' in log or 'sentence' in log]
        
        # Calculate perplexities in batches
        perplexities = []
        num_batches = (len(sentences) + calculator.batch_size - 1) // calculator.batch_size
        
        for i in tqdm(range(0, len(sentences), calculator.batch_size), 
                     total=num_batches, desc="Processing batches"):
            batch = sentences[i:i + calculator.batch_size]
            batch_perplexities = calculator.calculate_perplexity_batch(batch)
            perplexities.extend(batch_perplexities)
        
        # Update log entries with calculated perplexities
        perplexity_idx = 0
        for log_entry in data['logs']:
            if 'sentence' in log_entry:
                log_entry['perplexity'] = perplexities[perplexity_idx]
                perplexity_idx += 1
            elif 'molecule' in log_entry:
                log_entry['score'] = perplexities[perplexity_idx]
                perplexity_idx += 1
        

    
    # Build or update best_perplexity_evolution
    if rebuild_evolution and 'logs' in data:
        print("Building best_perplexity_evolution from logs...")
        data['best_perplexity_evolution'] = build_best_perplexity_evolution(data['logs'])
        print(f"  Found {len(data['best_perplexity_evolution'])} improvements")
    elif 'best_perplexity_evolution' in data:
        print(f"Updating {len(data['best_perplexity_evolution'])} best evolution entries (batched)...")
        
        # Extract sentences
        sentences = [entry['sentence'] for entry in data['best_perplexity_evolution'] if 'sentence' in entry]
        
        # Calculate perplexities in batches
        perplexities = []
        num_batches = (len(sentences) + calculator.batch_size - 1) // calculator.batch_size
        
        for i in tqdm(range(0, len(sentences), calculator.batch_size),
                     total=num_batches, desc="Processing evolution batches"):
            batch = sentences[i:i + calculator.batch_size]
            batch_perplexities = calculator.calculate_perplexity_batch(batch)
            perplexities.extend(batch_perplexities)
        
        # Update entries
        perplexity_idx = 0
        for entry in data['best_perplexity_evolution']:
            if 'sentence' in entry:
                entry['score'] = perplexities[perplexity_idx]
                perplexity_idx += 1
    
    # Write updated data back to file
    if not dry_run:
        # Create backup
        backup_path = Path.joinpath(filepath.parent, f"{filepath.stem}.backup.json")
        with open(backup_path, 'w') as f:
            with open(filepath, 'r') as original:
                json.dump(json.load(original), f, indent=2)
        print(f"Backup created: {backup_path}")
        
        # Write updated file
        with open(filepath, 'w') as f:
            json.dump(data, f, indent=2)
        print(f"Updated file written: {filepath}")
    else:
        print("Dry run - no changes written to disk")
    
    return data





def main():
    """Main function to process all JSON files in specified directory."""
    import argparse
    from pathlib import Path
    
    parser = argparse.ArgumentParser(
        description='Update perplexity scores in JSON files using an LLM with batching'
    )
    parser.add_argument(
        '--input_dir', 
        type=str, 
        default='output/', 
        help='The folder containing the JSON files to process (default: output/)'
    )
    parser.add_argument(
        '--model',
        type=str,
        default='meta-llama/Llama-3.1-8B',
        help='HuggingFace model name (default: meta-llama/Llama-3.1-8B)'
    )
    parser.add_argument(
        '--batch-size',
        type=int,
        default=8,
        help='Number of sentences to process in each batch (default: 8)'
    )
    parser.add_argument(
        '--pattern',
        type=str,
        default='*.json',
        help='File pattern to match (default: *.json)'
    )
    parser.add_argument(
        '--dry-run',
        action='store_true',
        help='Calculate perplexities but do not modify files'
    )
    parser.add_argument(
        '--files',
        nargs='+',
        help='Specific files to process (overrides --pattern)'
    )
    parser.add_argument(
        '--rebuild-evolution',
        action='store_true',
        default=True,
        help='Rebuild best_perplexity_evolution from logs (default: True)'
    )

    args = parser.parse_args()
    
    folder_path = Path(args.input_dir)
    

    if args.files:
        # If user explicitly specifies files, ensure they target the folder or handle them directly
        json_files = [Path(f) for f in args.files]
    else:
        json_files = list(folder_path.glob(args.pattern))
    
    if not json_files:
        print(f"No files found matching pattern: {args.pattern}")
        return
    
    print(f"Found {len(json_files)} file(s) to process")
    print(f"Files: {', '.join(str(f) for f in json_files)}")
    
    # Initialize calculator with batching
    calculator = PerplexityCalculator(model_name=args.model, batch_size=args.batch_size)
    
    
    # Process each file
    for filepath in json_files:
        if filepath.stem.endswith('.backup'):
            print(f"Skipping backup file: {filepath}")
            continue
        try:
            updated_data = update_json_file(
                filepath, 
                calculator, 
                dry_run=args.dry_run,
                rebuild_evolution=args.rebuild_evolution
            )
            
                
        except Exception as e:
            print(f"Error processing {filepath}: {e}")
            import traceback
            traceback.print_exc()
            continue
    
    print("\nProcessing complete!")


if __name__ == "__main__":
    main()