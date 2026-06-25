
# NeSyLNS: Neurosymbolic Large Neighborhood Search for Constrained Generation

This repository contains the evaluation framework and source code for our research article. Our system integrates Large Language Models (LLMs) with Constraint Programming (CP) via a hybrid Python/Java architecture to generate text and molecule sequences under strict structural constraints.


This codebase extends **MiniCPBP**, an open-source Java constraint programming solver.

* **MiniCPBP Framework:** Developed by Gilles Pesant, based on the original **MiniCP (v1.0)** core architecture designed by Laurent Michel, Pierre Schaus, and Pascal Van Hentenryck.
* **Core Reference:** The underlying algorithmic coupling of Constraint Programming and Belief Propagation implemented here is described in: *["Replacing Classic Propagation by Belief Propagation in MiniCP"](https://jair.org/index.php/jair/article/view/11487)* (Journal of Artificial Intelligence Research).

Any research utilizing this repository should appropriately cite both our research article and the foundational MiniCPBP work.

---

## 🏗️ Architecture Overview

The framework relies on a decoupled, two-part architecture:

1. **Python Neural Server:** Host the language models and provide token/sequence probabilities.
2. **Java CP-BP Engine (`MiniCPBP`):** Executes the combinatorial Large Neighborhood Search (LNS) using the MiniCPBP constraint engine, enforces structural constraints, and queries the Python servers via HTTP requests.

---

## 📁 Repository Structure

```text
NeSyLNS_article_repo/
├── pom.xml                        # MiniCPBP Maven project blueprint
├── requirements.txt                # Python library dependencies
├── server_mlm.py                  # Python server for NLP text generation tasks
├── server_molecules.py            # Python server for SMILES molecule generation tasks
├── perplexity_calculator.py       # Evaluates output sequence perplexity using an LLM
├── fluency_evaluator.py           # Processes outputs to generate summary statistics and graphs
├── run_nlp_pipeline.sh            # Cluster-ready pipeline script
├── run_molecule_pipeline.sh       # Cluster-ready pipeline script
└── src/
    └── main/
        └── java/
            └── minicpbp/          # Core MiniCPBP solver implementation
                └── examples/      # MiniCPBP model entry points and LNS execution
                    ├── data/MNREAD/
                    │   ├── ENGLISH_LEMMESET_CORRECTED+math.json  # Standard text vocabulary
                    │   ├── generate_model_vocabulary.py          # Token mapping generator
                    │   └── [model]/                              # Generated model token vocabulary mappings based on standard text vocabulary
                    └── config/
                        ├── refs/                                 # Reference sentences used as initial solutions
                        └── [constraint_models].java              # Task definitions


```

---

## 🚀 Environment Setup & Compilation

### 1. Python Environment

Ensure you have Python 3.9+ and a CUDA-compatible environment for LLM inference. You should download the library pyTorch based on your CUDA version at https://pytorch.org/get-started/locally/.

```bash
python3 -m venv venv
source venv/bin/activate  # On Windows: .\venv\Scripts\Activate.ps1
pip install -r requirements.txt

```

### 2. Compiling the MiniCPBP Shaded JAR (Required Before Running Pipelines)

The Java engine requires **JDK 1.8 or above** and **Apache Maven**.

Because cluster job scripts (`.sh` files) execute the pre-compiled code directly via raw Java instructions to avoid build-tool overhead on compute nodes, **you must compile the MiniCPBP project manually before launching any pipeline.**

Run the following command at the repository root to download external dependencies (such as ANTLR and Jackson) and package the solver into a standalone executable:

```bash
mvn clean install -Dmaven.test.skip=true

```

This generates the deployment-ready executable at `target/minicpbp-1.0-shaded.jar`. Whenever you modify the Java source files or add custom MiniCPBP branching strategies, you must rerun this command to re-bake the changes into the shaded JAR.

---

## 💻 Running Locally (Development Mode)

When developing or debugging locally on your machine, you must run the server and the MiniCPBP optimization client concurrently across separate terminal windows.

### Step A: Launch the Neural Server

```bash
# For NLP/text generation tasks:
python server_mlm.py

# For molecular generation (SMILES tracking):
python server_molecules.py

```

### Step B: Execute the Solver via Maven

Open a second terminal window and run your active model target. Maven will automatically track local compilation changes in this mode:

```bash
mvn exec:java -Dexec.mainClass="minicpbp.examples.YourActiveLNSModelClass" -Dexec.args="Your arguments"
mvn exec:java -D"exec.mainClass"="minicpbp.examples.YourActiveLNSModelClass" -D"exec.args"="Your arguments" # For Windows, require additional quotation marks

# Example
mvn exec:java -Dexec.mainClass="minicpbp.examples.NLP_MLM_CPBP" -Dexec.args="1.2 5000 output 10 3 CollieSent1_MLM_Config randomSentenceBuilder 50 0.2 CP_LLM"  

```

---

## 🏢 Running on Compute Clusters (e.g., Digital Alliance Canada / Slurm)

For heavy workloads deployed on remote high-performance computing nodes, use the provided `.sh` pipeline scripts. These scripts are self-sufficient regarding runtime execution. They automatically boot your background Python inference server on an allocated GPU node, and then launch the backend using the pre-compiled MiniCPBP JAR binary:

```bash
bash example_experiments.sh

```

---

## 🛠️ Adding New Baselines and Models (Extension Guide)

Integrating a new Large Language Model or setting up a brand-new experimental baseline requires synchronization between the tokenization layout and the MiniCPBP search domains.

### 1. Step A: Vocabulary Extraction

Different LLMs use entirely different tokenizers. The MiniCPBP engine must know exactly which token ID corresponds to which word or sub-word to bound its variable search spaces.

To add support for a new model, navigate to the vocabulary directory and run the extractor script:

```bash
cd src/main/java/minicpbp/examples/data/MNREAD/
python generate_model_vocabulary.py --model your-chosen-hf-repo

```

This produces a new folder containing structured JSON vocabulary maps linking string tokens to internal network integers.

### 2. Step B: Update Java Argument Parsing

When adding a new model file or establishing a baseline entry point under `src/main/java/minicpbp/examples/`, you will need to make **minimal modifications to the argument parsing code** within that specific Java class file.

Ensure that your target file's CLI parser includes flags to dynamically read:

* The path to your newly generated vocabulary JSON map to replace the curren llm_name variable.
* The matching server running the desired model.
* Task-specific constraint model and reference sentences located within the `config/` subfolder (e.g., modify the Collie tasks arguments, adding new tasks or changing the initial sentences).

*Remember to execute `mvn clean install -Dmaven.test.skip=true` after making these adjustments so your job scripts see the updated MiniCPBP argument definitions.*

---

## 📊 Evaluation & Artifact Processing

Once a generation run completes, use the processing utilities to run metrics over the generated outputs folder:

1. **Calculate Sequence Perplexity:**
Computes token-level perplexity for output files in the current directory and updates them using an evaluation LLM (Defaults to `llama3.1-8B`):
```bash
python perplexity_calculator.py --input_dir ./output

```


2. **Generate Final Analytics & Graphs:**
Processes the updated output files to compute final evaluation metrics (including BLEU diversity evaluations and sentence constraints tracking) and exports localized vector graphics:
```bash
python fluency_evaluator.py --input_dir ./output

```