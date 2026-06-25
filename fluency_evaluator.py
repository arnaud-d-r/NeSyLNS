import json
import os
from pathlib import Path
import torch
import numpy as np
import time
from tqdm import tqdm
import argparse
import math
from collections import defaultdict
import csv
import nltk
nltk.download('punkt', quiet=True)

from nltk.translate.bleu_score import sentence_bleu, SmoothingFunction
import numpy as np

parser = argparse.ArgumentParser(description="Evaluate fluency and generate summary results/graphs.")
parser.add_argument(
    "--input_dir", 
    type=str, 
    default="output/", 
    help="The folder containing the JSON files to process (default: output/)"
)
parser.add_argument(
    "--skip", 
    type=str, 
    nargs="*", 
    default=[], 
    help="Space-separated list of filenames to skip (e.g., --skip file1.json file2.json)"
)
args = parser.parse_args()

# Dynamically assign the path based on the user's input
folder_path = Path(args.input_dir)

# Convert the skipped files list into a set for fast O(1) lookups during processing
skipped_files = set(args.skip)

def self_bleu(sentences, isMolecule=False):
    smooth = SmoothingFunction().method1
    scores = []
    
    for i, hypothesis in enumerate(sentences):
        if isMolecule:
            hypothesis_tokens = tokenize_v7(hypothesis)
            references = [tokenize_v7(s) for j, s in enumerate(sentences) if i != j]
        else:
            references = [s.split() for j, s in enumerate(sentences) if i != j]
            hypothesis_tokens = hypothesis.split()
        score = sentence_bleu(references, hypothesis_tokens, 
                             smoothing_function=smooth)
        scores.append(score)
    
    return np.mean(scores)  # Lower = more diverse

def bleu_vs_reference(sentences, reference, isMolecule=False):
    smooth = SmoothingFunction().method1
    scores = []

    if isMolecule:
        ref_tokens = tokenize_v7(reference)
    else:
        ref_tokens = reference.split()

    for hypothesis in sentences:
        if isMolecule:
            hypothesis_tokens = tokenize_v7(hypothesis)
        else:
            hypothesis_tokens = hypothesis.split()
        
        score = sentence_bleu([ref_tokens], hypothesis_tokens, 
                            smoothing_function=smooth)
        scores.append(score)

    return np.mean(scores) if scores else 0.0

def tokenize_v7(molecule):
    """
    Tokenize a molecule string (likely SMILES notation).
    
    Args:
        molecule: String representation of molecule
        
    Returns:
        List of tokens
    """
    molecule_chars = list(molecule)
    tokens = []
    i = 0
    
    while i < len(molecule_chars):
        # Handle % followed by two characters
        if molecule_chars[i] == '%':
            tokens.append(f"%{molecule_chars[i+1]}{molecule_chars[i+2]}")
            i += 3
            continue
        
        # Handle Cl (Chlorine)
        elif i < len(molecule_chars) - 1 and molecule_chars[i] == 'C' and molecule_chars[i+1] == 'l':
            tokens.append("Cl")
            i += 2
            continue
        
        # Handle Br (Bromine)
        elif i < len(molecule_chars) - 1 and molecule_chars[i] == 'B' and molecule_chars[i+1] == 'r':
            tokens.append("Br")
            i += 2
            continue
        
        # Handle H3
        elif i < len(molecule_chars) - 1 and molecule_chars[i] == 'H' and molecule_chars[i+1] == '3':
            tokens.append("H3")
            i += 2
            continue
        
        # Single character token
        tokens.append(molecule_chars[i])
        i += 1
    
    return tokens

device_gpu = "cuda" if torch.cuda.is_available() else "cpu"
device_cpu = "cpu"

start_time = time.time()    








# Data structure to store multiple lists of (score, time) pairs for each combination
score_time_data = {}

def add_score_time(architecture, config, sentenceBuilder,top_k, mask_percentage, score_timestamp_list,seed, ref=None ):
    key = (architecture, config, sentenceBuilder, top_k, mask_percentage, ref, seed)
    if key not in score_time_data:
        score_time_data[key] = []
    score_time_data[key].append(score_timestamp_list)

all_score_time = {}
def add_all_score_time(architecture, config, sentenceBuilder,top_k, mask_percentage, score_timestamp_list,seed, ref=None ):
    key = (architecture, config, sentenceBuilder, top_k, mask_percentage, ref, seed)
    if key not in all_score_time:
        all_score_time[key] = []
    all_score_time[key].append(score_timestamp_list)


base_seed = {}

all_summary = {}
all_summary = defaultdict(lambda: defaultdict(dict))

def accumulate_summary(architecture, problem, sentenceBuilder, ref, summary, seed):
    """Accumulate summary statistics by problem, ref, sentenceBuilder, and architecture."""
    print(f"{architecture}, {problem}, {sentenceBuilder}, {ref}, {seed} added")
    key = (problem, ref, sentenceBuilder, architecture)
    if key not in all_summary:
        all_summary[key] = {
            "PPL_avg": [],
            "PPL_min": [],
            "PPL_max": [],
            "PPL_median": [],
            "number_solutions": [],
            "time_per_run": [],
            "time_per_solution": [],
            "number_solutions_under_base": [],
            "failed_attempts": [],
            "self_bleu": [],
            "bleu": [],
            "seed_no_improvement": [],
            "search_time": [],
            "search_time_per_solution": []
        }
        
    if summary.get("number_solutions") == 0:
        all_summary[key]["failed_attempts"].append(1)
        return
    all_summary[key]["PPL_avg"].append(summary.get("PPL_avg"))
    all_summary[key]["PPL_min"].append(summary.get("PPL_min"))
    all_summary[key]["PPL_max"].append(summary.get("PPL_max"))
    all_summary[key]["PPL_median"].append(summary.get("PPL_median"))
    all_summary[key]["number_solutions"].append(summary.get("number_solutions"))
    all_summary[key]["time_per_solution"].append(summary.get("total_time_per_solution"))
    all_summary[key]["time_per_run"].append(summary.get("total_time_seconds"))
    all_summary[key]["number_solutions_under_base"].append(summary.get("number_solutions_under_base"))
    all_summary[key]["failed_attempts"].append(1 if summary.get("number_solutions") == 0 else 0)
    all_summary[key]["self_bleu"].append(summary.get("self_bleu"))
    all_summary[key]["bleu"].append(summary.get("bleu"))
    all_summary[key]["seed_no_improvement"].append(1 if summary.get("number_solutions_under_base") == 0 else 0)
    all_summary[key]["search_time"].append(summary.get("search_time"))
    all_summary[key]["search_time_per_solution"].append(summary.get("search_time_per_solution"))
    

for file_path in folder_path.glob("*.json"):
    if "evaluation_results_" in file_path.stem or "_error" in file_path.stem or "backup" in file_path.stem:
        continue  # skip already processed files
    print("Processing:", file_path)

    if file_path.name in skipped_files:
        print(f"Skipping {file_path.name} as per user request.")
        continue
    
    # Example: load JSON content
    with open(file_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    print("Loaded JSON data from", file_path)
    # --- 1. Load sentences from JSON ---
    sentences = [
        entry
        for entry in data["logs"]
        if not entry["sentence"].strip().endswith("ERROR")
    ]
    isMolecule = False
    if "MOLECULE" in file_path.stem:
        isMolecule=True
        ref_file = data.get("reference_file", "unknown")
        if "no_gpt" in ref_file:
            ref = "CPBP"
        else:
            ref = "GeAI-BLAnC"
        problem = "molecules"
    else:   
        ref_type = data.get("ref_type", "DEFAULT")
        match ref_type:
            case "DEFAULT":    
                ref = "DEFAULT"
                
            case "CP_LLM":
                ref = "CP+LLM"
                
            case "CP":
                ref = "CP"
            case "BONLARRON":
                ref = "Bonlarron et al. (2023)"
                
            case "AUTHORS":
                ref = "Mansfield et al. (2019)"
                
        problem = data["config"]
    print("set problem to", problem)    
    
    sentenceBuilder = data.get("sentence_builder", "random")
    if "random" in sentenceBuilder:
        sentenceBuilder = "Random Masking"
    elif "perplexity" in sentenceBuilder:
        sentenceBuilder = "Perplexity Masking"
    else:
        raise ValueError(f"Unknown sentence builder: {sentenceBuilder}")
    seed = data.get("seed", "unknown")
    top_k = data.get("oracle_top_k", "unknown")
    mask_percentage = data.get("mask_percent", "unknown")
    if isMolecule:
        architecture = data.get("config", "default")
    else :
        architecture = file_path.stem
    if "CPBP_PLUS" in architecture:
        architecture = "CPBP+"
    elif "CP" in architecture:
        architecture = "CP"
    elif "CPBP" in architecture:
        architecture = "CPBP"
    else:
        raise ValueError(f"Config 'name' must contain 'CP', 'CPBP' or 'CPBP_PLUS', got: {file_path.stem}")
    

    
    base_seed_key = (problem,seed, ref)
    base_sentence = data.get("base_sentence")
    base_score = base_sentence.get("perplexity") or base_sentence.get("score")
    if base_seed_key not in base_seed:
        base_seed[base_seed_key] = base_score
    
    best_time_evolution_list = []
    for event in data["best_perplexity_evolution"]:         

        score = event.get("score")
        score /=base_score
        time_evolution = event.get("time")/1000
        # Additional info available
        
        if score is not None and time_evolution is not None:
            try:
                best_time_evolution_list.append((float(score), int(time_evolution)))
            except Exception:
                pass

        # when we've reached the last event, add the whole evolution to score_time_data
        if event is data["best_perplexity_evolution"][-1]:
            add_score_time(architecture, problem, sentenceBuilder, top_k, mask_percentage, best_time_evolution_list, seed, ref)
            
   
    if not sentences:        
        summary = {
            "number_solutions": 0, 
        }
        accumulate_summary(architecture, problem, sentenceBuilder, ref, summary, seed)    
        continue
    
    # --- 4. Run evaluation ---
    results = []
    for sent in tqdm(sentences, desc="Evaluating sentences"):
        s=sent["sentence"].strip()
        #llm_score = evaluate_fluency_llm(s)
        ppl_score = sent["perplexity"]
        time_taken = sent["timestamp"]
        results.append({"sentence": s, "perplexity": ppl_score, "time": time_taken})

    # --- 5. Summary stats ---
    #llm_scores = [r["LLM_fluency"] for r in results if r["LLM_fluency"] is not None]
    ppl_scores = [r["perplexity"] for r in results]
    
    all_time_evolution_list = []
    for event in results:         

        score = event.get("score") or event.get("perplexity")
        score /=base_score
        time_evolution = event.get("time")/1000
        # Additional info available
        
        if score is not None and time_evolution is not None:
            try:
                all_time_evolution_list.append((float(score), int(time_evolution)))
            except Exception:
                pass

        # when we've reached the last event, add the whole evolution to score_time_data
        if event is results[-1]:
            add_all_score_time(architecture, problem, sentenceBuilder, top_k, mask_percentage, all_time_evolution_list, seed, ref)
    
    
    results.sort(key=lambda r: r["perplexity"])
    
    

    summary = {
        #"LLM_avg": float(np.mean(llm_scores)) if llm_scores else None,
        #"LLM_min": float(np.min(llm_scores)) if llm_scores else None,
        #"LLM_max": float(np.max(llm_scores)) if llm_scores else None,
        "PPL_avg": float(np.mean(ppl_scores)) if ppl_scores else None,
        "PPL_min": float(np.min(ppl_scores)) if ppl_scores else None,
        "PPL_max": float(np.max(ppl_scores)) if ppl_scores else None,
        "PPL_median": float(np.median(ppl_scores)) if ppl_scores else None,
        "PPL_first_quartile": float(np.percentile(ppl_scores, 25)) if ppl_scores else None,
        "PPL_third_quartile": float(np.percentile(ppl_scores, 75)) if ppl_scores else None,
        "number_solutions": len(results), 
        "total_time_seconds": data.get("time", None),
        "search_time": data.get("time", None)- best_time_evolution_list[-1][1] if best_time_evolution_list else None,
        "total_time_per_solution": data.get("time", None) / len(results) if results and data.get("time", None) is not None else None,
        "search_time_per_solution": (data.get("time", None)- best_time_evolution_list[-1][1]) / len(results) if results and data.get("time", None) is not None and best_time_evolution_list else None,
        "base_score": base_score,
        "number_solutions_under_base": sum(1 for r in results if r["perplexity"] < base_score),
        "self_bleu": self_bleu([r["sentence"] for r in results], isMolecule) if len(results) > 1 else None,
        "bleu": bleu_vs_reference([r["sentence"] for r in results], base_sentence["molecule"] if isMolecule else base_sentence["sentence"], isMolecule),
    }
    accumulate_summary(architecture, problem, sentenceBuilder, ref, summary, seed)
    
    
    # --- 6. Save results ---
    # Find best sentences based on scores
    #best_llm = max(results, key=lambda r: r["LLM_fluency"] if r["LLM_fluency"] is not None else float('-inf'))
    best_ppl = min(results, key=lambda r: r["perplexity"])

    characteristics = {
        "architecture": architecture,
        "problem": problem,
        "sentenceBuilder": sentenceBuilder,
        "top_k": top_k,
        "number_iterations": data.get("num_iterations", None),
        "mask_percentage": mask_percentage,
        "ref": ref,
        "seed": seed,
        "base_sentence": data.get("base_sentence", None),
    }
    

    output_data = {
        "characteristics": characteristics,
        "summary": summary,
        #"best_LLM_fluency": best_llm,
        "best_perplexity": best_ppl,
        "results": results,
    }
 
    print("Evaluation done. Summary:")
    end_time = time.time()
    print(f"Total evaluation time: {end_time - start_time:.2f} seconds")

# Calculate average base score for each (problem, ref) combination
base_seed_by_problem_ref = defaultdict(list)
for (problem, seed, ref), score in base_seed.items():
    base_seed_by_problem_ref[(problem, ref)].append(score)

base_seed_avg = {
    key: float(np.mean(scores)) for key, scores in base_seed_by_problem_ref.items()
}

# Aggregate all_summary statistics
aggregated_stats = {}
for key, metrics in all_summary.items():
    
    problem, ref, sentenceBuilder, architecture = key
    agg_key = (problem, ref, sentenceBuilder, architecture)
    if len(metrics["number_solutions"])!=15:
        print(f"Warning: unexpected metrics length for key {key}: {len(metrics["number_solutions"])}")
    
    aggregated_stats[agg_key] = {
        "PPL_avg": float(np.mean([v for v in metrics["PPL_avg"] if v is not None])) if metrics["PPL_avg"] else None,
        "PPL_min": float(np.mean([v for v in metrics["PPL_min"] if v is not None])) if metrics["PPL_min"] else None,
        "PPL_max": float(np.mean([v for v in metrics["PPL_max"] if v is not None])) if metrics["PPL_max"] else None,
        "PPL_median": float(np.mean([v for v in metrics["PPL_median"] if v is not None])) if metrics["PPL_median"] else None,
        "number_solutions": sum([v for v in metrics["number_solutions"] if v is not None]),
        "time_per_solution": float(np.mean([v for v in metrics["time_per_solution"] if v is not None])) if metrics["time_per_solution"] else None,
        "time_per_run": float(np.mean([v for v in metrics["time_per_run"] if v is not None])) if metrics["time_per_run"] else None,
        "number_solutions_under_base": sum([v for v in metrics["number_solutions_under_base"] if v is not None]),
        "failed_attempts": sum([v for v in metrics["failed_attempts"] if v is not None]),
        "self_bleu": float(np.mean([v for v in metrics["self_bleu"] if v is not None])),
        "bleu": float(np.mean([v for v in metrics["bleu"] if v is not None])),
        "seed_no_improvement": sum([v for v in metrics["seed_no_improvement"] if v is not None]),
        "Base_PPL": base_seed_avg.get((problem, ref), None),
        "search_time": float(np.mean([v for v in metrics["search_time"] if v is not None])) if metrics["search_time"] else None,
        "search_time_per_solution": float(np.mean([v for v in metrics["search_time_per_solution"] if v is not None])) if metrics["search_time_per_solution"] else None,
    }

# Write to text file
summary_file = Path(folder_path) / f"aggregated_summary_{problem}.csv"
with open(summary_file, "w", encoding="utf-8") as f:
    # Prepare CSV data
    csv_rows = []
    for (problem, ref, sentenceBuilder, architecture), stats in sorted(aggregated_stats.items()):
        row = {
            "Problem": problem,
            "Ref": ref,
            "SentenceBuilder": sentenceBuilder,
            "Architecture": architecture,
        }
        row.update(stats)
        csv_rows.append(row)
    
    # Write CSV
    if csv_rows:
        fieldnames = ["Problem", "Ref", "SentenceBuilder", "Architecture"] + list(csv_rows[0].keys())[4:]
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(csv_rows)

print(f"Summary written to {summary_file}")

import matplotlib.pyplot as plt
import matplotlib.ticker as mticker


plots_dir = Path(folder_path) / "plots"
plots_dir.mkdir(parents=True, exist_ok=True)

# Group data only by problem_key, and within that group by (ref, seed)
problems = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))

for (arch, problem_key, sentenceBuilder, top_k, mask_percentage, ref, seed), runs in score_time_data.items():
    key = (arch, sentenceBuilder)  # only varying parameters
    problems[problem_key][(ref, seed)][key].extend(runs)


# Colors per architecture (3)
ARCH_COLORS = {
    "CPBP+": "#1f77b4",
    "CPBP": "#2ca02c",
    "CP": "#ff7f0e",
}

# Linestyles per sentence builder (2)
SB_STYLE = {
    "Random Masking": "-",
    "Perplexity Masking": "--",
}

for problem_key, refseed_data in problems.items():

    refs = sorted(set(ref for (ref, seed) in refseed_data.keys()))

    for ref in refs:
        seeds = sorted(seed for (r, seed) in refseed_data.keys() if r == ref)
        n = len(seeds)
        if n == 0:
            continue

        ncols = min(2, n)
        nrows = math.ceil(n / ncols)
        fig, axes = plt.subplots(nrows, ncols, figsize=(6 * ncols, 4 * nrows), squeeze=False)
        axes_flat = axes.flatten()

        max_score_global = 0

        for idx, seed in enumerate(seeds):
            ax = axes_flat[idx]
            configs = refseed_data[(ref, seed)]
            plotted = False
            max_score_subplot = 0

            for (arch, sb), runs in sorted(configs.items()):
                if not runs:
                    continue

                run = runs[0]
                pts = [(float(s), int(t)) for s, t in run if s is not None and t is not None]
                if not pts:
                    continue

                pts.sort(key=lambda x: x[1])
                scores = [p[0] for p in pts]
                times = [p[1] for p in pts]
                t0 = times[0]
                rel_times = [(t - t0) / 60.0 for t in times]

                max_score_subplot = max(max_score_subplot, max(scores))
                max_score_global = max(max_score_global, max(scores))

                label = f"{arch} | {sb}"
                ax.plot(
                    rel_times,
                    scores,
                    marker="o",
                    label=label,
                    linestyle=SB_STYLE.get(sb, "-"),
                    color=ARCH_COLORS.get(arch, "black"),
                )

                plotted = True

            base_key = (problem_key, seed, ref)
            if base_key in base_seed:
                base_ppl = base_seed[base_key]
                ax.axhline(
                    y=1,
                    color="red",
                    linestyle=":",
                    linewidth=2,
                    label=f"Base PPL: {base_ppl:.2f}",
                )

            if plotted:
                ax.set_xlabel("Time (minutes)")
                ax.set_ylabel("Perplexity compared to the base")
                ax.grid(True)
                ax.set_yscale("log")
                ax.yaxis.set_major_formatter(mticker.ScalarFormatter())
                #legend = ax.legend(fontsize=8)
                #for handle in legend.legend_handles:
                #    handle.set_marker(' ')
                title = f"Seed: {seed}"
                ax.set_title(title)
                if max_score_subplot > 5:
                    ax.set_ylim(0, 5)
            else:
                ax.axis("off")

        for j in range(n, len(axes_flat)):
            axes_flat[j].axis("off")

        fig.suptitle(f"Score evolution for problem: {problem_key} {'| Ref: ' + ref if ref is not None else ''}", fontsize=16)
        fig.tight_layout(rect=[0, 0, 1, 0.96])

        if ref is None:
            plt.savefig(f"{plots_dir}/problem_{problem_key}.png")
        else:
            plt.savefig(f"{plots_dir}/problem_{problem_key}_ref_{ref}.png")


# -------------------------------------------------------
# NEW: For each problem + ref, aggregate over seeds using LAST VALUE ≤ time
# -------------------------------------------------------
for problem_key, refseed_data in problems.items():

    refs = sorted(set(ref for (ref, seed) in refseed_data.keys()))

    for ref in refs:
        seeds = sorted(seed for (r, seed) in refseed_data.keys() if r == ref)
        if not seeds:
            continue

        # Structure:
        # raw[(arch, sb)][seed] = list of (time, score)
        raw = defaultdict(lambda: defaultdict(list))

        all_times = set()

        # Collect data
        for seed in seeds:
            configs = refseed_data[(ref, seed)]

            for (arch, sb), runs in configs.items():
                if not runs:
                    continue

                run = runs[0]
                pts = [(float(s), int(t)) for s, t in run if s is not None and t is not None]
                if not pts:
                    continue

                pts.sort(key=lambda x: x[1])
                scores = [p[0] for p in pts]
                times = [p[1] for p in pts]

                t0 = times[0]
                rel_times = [t - t0 for t in times]

                # store cleaned points
                for t, sc in zip(rel_times, scores):
                    raw[(arch, sb)][seed].append((t, sc))
                    all_times.add(t)

        # unified timeline
        all_times = sorted(all_times)

        # -------------------------------------------------
        # Figure creation
        # -------------------------------------------------
        fig, ax = plt.subplots(figsize=(8, 5))
        base_ppl = base_seed_avg.get((problem_key, ref), None)

        for (arch, sb), seed_dict in raw.items():

            # For each seed, forward-fill scores on the global time axis
            filled_scores = []

            for seed, pts in seed_dict.items():
                pts.sort()
                seed_vals = []
                idx = 0
                last_val = None

                for T in all_times:
                    # Advance pointer while pts[idx].time ≤ T
                    while idx < len(pts) and pts[idx][0] <= T:
                        last_val = pts[idx][1]
                        idx += 1
                    seed_vals.append(last_val)  # may be None

                filled_scores.append(seed_vals)

            # Convert to numpy for stats
            arr = np.array(filled_scores, dtype=float)  # shape = (num_seeds, num_times)

            # Ignore missing values safely
            means = np.nanmean(arr, axis=0)
            mins  = np.nanmin(arr, axis=0)
            maxs  = np.nanmax(arr, axis=0)

            color = ARCH_COLORS.get(arch, "black")
            linestyle = SB_STYLE.get(sb, "-")
            label = f"{arch} | {sb}"

            # Plot mean
            ax.plot(all_times, means, color=color, linestyle=linestyle,
                    label=label, linewidth=2)

            # Shade min/max
            ax.fill_between(all_times, mins, maxs,
                            color=color, alpha=0.18)

        ax.set_xlabel("Time (seconds from first solution)")
        ax.set_ylabel("Perplexity ratio compared to the base")
        ax.set_title(f"Problem {problem_key} {'— Ref: ' + ref if ref is not None else ''}\nMean ± Min/Max with forward-fill ({len(seeds)} seeds)")
        ax.grid(True)
        ax.yaxis.set_major_formatter(mticker.ScalarFormatter())
        ax.set_yscale("log")
        leg = ax.legend(loc="center left", bbox_to_anchor=(1, 0.5))
        ax.axhline(
                y=1,
                color="red",
                linestyle=":",
                linewidth=2,
                label=f"Base PPL: {base_ppl:.2f}",
            )
        leg.remove()
        fig.tight_layout()
        if ref is None:
            plt.savefig(f"{plots_dir}/problem_{problem_key}_mean_minmax.png")
        else:
            plt.savefig(f"{plots_dir}/problem_{problem_key}_ref_{ref}_mean_minmax.png")



problems2 = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))
for (arch, problem_key, sentenceBuilder, top_k, mask_percentage, ref, seed), runs in all_score_time.items():
    key = (arch, sentenceBuilder)  # only varying parameters
    problems2[problem_key][(ref, seed)][key].extend(runs)



for problem_key, refseed_data in problems2.items():

    # Extraire toutes les refs
    refs = sorted(set(ref for (ref, seed) in refseed_data.keys()))

    for ref in refs:
        # Extraire les seeds pour cette ref
        seeds = sorted(seed for (r, seed) in refseed_data.keys() if r == ref)
        n = len(seeds)
        if n == 0:
            continue

        # Créer la grille de subplots
        ncols = min(2, n)
        nrows = math.ceil(n / ncols)
        fig, axes = plt.subplots(nrows, ncols, figsize=(6 * ncols, 4 * nrows), squeeze=False)
        axes_flat = axes.flatten()

        max_score_global = 0

        for idx, seed in enumerate(seeds):
            ax = axes_flat[idx]
            configs = refseed_data[(ref, seed)]
            plotted = False
            max_score_subplot = 0

            # Tracer chaque (arch, sentenceBuilder)
            for (arch, sb), runs in sorted(configs.items()):
                if not runs:
                    continue

                run = runs[0]
                pts = [(float(s), int(t)) for s, t in run if s is not None and t is not None]
                if not pts:
                    continue

                pts.sort(key=lambda x: x[1])
                scores = [p[0] for p in pts]
                times = [p[1] for p in pts]
                t0 = times[0]
                rel_times = [(t - t0) / 60.0 for t in times]

                max_score_subplot = max(max_score_subplot, max(scores))
                max_score_global = max(max_score_global, max(scores))

                label = f"{arch} | {sb}"
                ax.plot(
                    rel_times,
                    scores,
                    marker="o",
                    label=label,
                    linestyle=SB_STYLE.get(sb, "-"),
                    color=ARCH_COLORS.get(arch, "black"),
                )

                plotted = True

            # Ligne de base
            base_key = (problem_key, seed, ref)
            if base_key in base_seed:
                base_ppl = base_seed[base_key]
                ax.axhline(
                    y=1,
                    color="red",
                    linestyle=":",
                    linewidth=2,
                    label=f"Base PPL: {base_ppl:.2f}",
                )

            if plotted:
                ax.set_xlabel("Time (minutes)")
                ax.set_ylabel("Perplexity compared to the base")
                ax.grid(True)
                ax.set_yscale("log")
                ax.yaxis.set_major_formatter(mticker.ScalarFormatter())
                # = ax.legend(fontsize=8)
                ##for handle in legend.legend_handles:
                #    handle.set_marker(' ')
                title = f"Seed: {seed}"
                ax.set_title(title)
                if max_score_subplot > 5:
                    ax.set_ylim(0, 5)
            else:
                ax.axis("off")

        # Masquer les axes inutilisés
        for j in range(n, len(axes_flat)):
            axes_flat[j].axis("off")

        fig.suptitle(f"Score evolution for problem: {problem_key} {'| Ref: ' + ref if ref is not None else ''}", fontsize=16)
        fig.tight_layout(rect=[0, 0, 1, 0.96])

        if ref is None:
            plt.savefig(f"{plots_dir}/problem_{problem_key}_all.png")
        else:
            plt.savefig(f"{plots_dir}/problem_{problem_key}_ref_{ref}_all.png")

    
    # -------------------------------------------------------
# NEW: For each problem + ref, aggregate over seeds using LAST VALUE ≤ time
# -------------------------------------------------------
for problem_key, refseed_data in problems2.items():

    refs = sorted(set(ref for (ref, seed) in refseed_data.keys()))

    for ref in refs:
        seeds = sorted(seed for (r, seed) in refseed_data.keys() if r == ref)
        if not seeds:
            continue

        # Structure:
        # raw[(arch, sb)][seed] = list of (time, score)
        raw = defaultdict(lambda: defaultdict(list))

        all_times = set()

        # Collect data
        for seed in seeds:
            configs = refseed_data[(ref, seed)]

            for (arch, sb), runs in configs.items():
                if not runs:
                    continue

                run = runs[0]
                pts = [(float(s), int(t)) for s, t in run if s is not None and t is not None]
                if not pts:
                    continue

                pts.sort(key=lambda x: x[1])
                scores = [p[0] for p in pts]
                times = [p[1] for p in pts]

                t0 = times[0]
                rel_times = [t - t0 for t in times]

                # store cleaned points
                for t, sc in zip(rel_times, scores):
                    raw[(arch, sb)][seed].append((t, sc))
                    all_times.add(t)

        # unified timeline
        all_times = sorted(all_times)

        # -------------------------------------------------
        # Figure creation
        # -------------------------------------------------
        fig, ax = plt.subplots(figsize=(8, 5))

        for (arch, sb), seed_dict in raw.items():

            # For each seed, forward-fill scores on the global time axis
            filled_scores = []

            for seed, pts in seed_dict.items():
                pts.sort()
                seed_vals = []
                idx = 0
                last_val = None

                for T in all_times:
                    # Advance pointer while pts[idx].time ≤ T
                    while idx < len(pts) and pts[idx][0] <= T:
                        last_val = pts[idx][1]
                        idx += 1
                    seed_vals.append(last_val)  # may be None

                filled_scores.append(seed_vals)

            # Convert to numpy for stats
            arr = np.array(filled_scores, dtype=float)  # shape = (num_seeds, num_times)

            # Ignore missing values safely
            means = np.nanmean(arr, axis=0)
            mins  = np.nanmin(arr, axis=0)
            maxs  = np.nanmax(arr, axis=0)

            color = ARCH_COLORS.get(arch, "black")
            linestyle = SB_STYLE.get(sb, "-")
            label = f"{arch} | {sb}"

            # Plot mean
            ax.plot(all_times, means, color=color, linestyle=linestyle,
                    label=label, linewidth=2)

            # Shade min/max
            ax.fill_between(all_times, mins, maxs,
                            color=color, alpha=0.18)

        ax.set_xlabel("Time (seconds from first solution)")
        ax.set_ylabel("Perplexity compared to the base")
        ax.set_title(f"Problem {problem_key} {'— Ref: ' + ref if ref is not None else ''}\nMean ± Min/Max with forward-fill ({len(seeds)} seeds)")
        ax.grid(True)
        ax.yaxis.set_major_formatter(mticker.ScalarFormatter())
        ax.set_yscale("log")
        #ax.legend(loc="center left", bbox_to_anchor=(1, 0.5))
        ax.axhline(
                y=1,
                color="red",
                linestyle=":",
                linewidth=2,
                label=f"Base PPL: {base_ppl:.2f}",
            )
        fig.tight_layout()
        if ref is None:
            plt.savefig(f"{plots_dir}/problem_{problem_key}_all_mean_minmax.png")
        else:
            plt.savefig(f"{plots_dir}/problem_{problem_key}_ref_{ref}_all_mean_minmax.png")

