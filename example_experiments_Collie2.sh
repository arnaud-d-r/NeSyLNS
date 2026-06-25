

module load java/21.0.1
source venv/bin/activate
unset JAVA_TOOL_OPTIONS

export TRANSFORMERS_OFFLINE=1
export HF_HUB_OFFLINE=1

(
    python server_mlm.py
) > server_mlm.log 2>&1 &
SERVER_PID=$!

export TRANSFORMERS_OFFLINE=1
export HF_HUB_OFFLINE=1


sleep 30

until curl -s http://localhost:5000/ping | grep -q "pong"; do
    echo "Serveur pas encore prêt... attente..."
    sleep 1
done
echo "Server is ready!"

# Define lists for the last two arguments
SEED_LIST=(0 1 2 3 4 5 6 7 8 9 10 11 12)
TASK_CONFIG_LIST=("CollieSent2_MLM_Config")
SENTENCE_BUILDER_LIST=("randomSentenceBuilder" "perplexitySentenceBuilder")

REF_TYPE_LIST=("CP" "CP_LLM")

# Maximum parallel jobs (CPU-bound, adjust based on available CPUs)
MAX_PARALLEL=8

# Counter for parallel jobs
job_count=0


# Loop through combinations
pids=()
for refType in "${REF_TYPE_LIST[@]}"; do
    for oracle_top_k in 50; do  ##Modified to complete missing loops (10 25)
        for mask_percent in 0.2 ; do
            for seed in "${SEED_LIST[@]}"; do
                for taskConfig in "${TASK_CONFIG_LIST[@]}"; do
                    for sentenceBuilder in "${SENTENCE_BUILDER_LIST[@]}"; do
                        echo "Running experiments with seed: ${seed}, taskConfig: ${taskConfig}, and sentenceBuilder: ${sentenceBuilder}, mask_percent: ${mask_percent}"
                    
                        # Run both Java commands in background (they'll queue requests to the Python server)
                        java -cp target/minicpbp-1.0.jar minicpbp.examples.NLP_MLM_CPBP_PLUS 1.2 5000 output 100 ${seed} ${taskConfig} ${sentenceBuilder} ${oracle_top_k} ${mask_percent} ${refType} &
                        pids+=($!)
                        java -cp target/minicpbp-1.0.jar minicpbp.examples.NLP_MLM_CP 1.2 5000 output 100 ${seed} ${taskConfig} ${sentenceBuilder} ${oracle_top_k} ${mask_percent} ${refType} &
                        pids+=($!)
                        java -cp target/minicpbp-1.0.jar minicpbp.examples.NLP_MLM_CPBP 1.2 5000 output 100 ${seed} ${taskConfig} ${sentenceBuilder} ${oracle_top_k} ${mask_percent} ${refType} &
                        pids+=($!)
                        job_count=$((job_count + 3))
                        
                        # Wait when we reach max parallel jobs
                        while [ $job_count -ge $MAX_PARALLEL ]; do
                            wait -n
                            job_count=$((job_count - 1))
                        done
                    done
                done
            done
        done
    done
done

# Wait for all remaining jobs to complete
for pid in "${pids[@]}"; do
    wait $pid
done

kill $SERVER_PID