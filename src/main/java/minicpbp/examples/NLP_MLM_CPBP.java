/*
 * mini-cp is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License  v3
 * as published by the Free Software Foundation.
 *
 * mini-cp is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY.
 * See the GNU Lesser General Public License  for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with mini-cp. If not, see http://www.gnu.org/licenses/lgpl-3.0.en.html
 *
 * Copyright (c)  2018. by Laurent Michel, Pierre Schaus, Pascal Van Hentenryck
 *
 * mini-cpbp, replacing classic propagation by belief propagation
 * Copyright (c)  2019. by Gilles Pesant
 */

package minicpbp.examples;

import minicpbp.cp.Factory;
import minicpbp.engine.constraints.Circuit;
import minicpbp.engine.constraints.Element1D;
import minicpbp.engine.constraints.LessOrEqual;
import minicpbp.engine.constraints.Markov;
import minicpbp.engine.constraints.NegTableCT;
import minicpbp.engine.core.BoolVar;
import minicpbp.engine.core.Constraint;
import minicpbp.engine.core.IntVar;
import minicpbp.engine.core.Solver;
import minicpbp.search.DFSearch;
import minicpbp.search.LDSearch;
import minicpbp.search.Objective;
import minicpbp.util.exception.InconsistencyException;
import minicpbp.util.io.InputReader;
import minicpbp.search.SearchStatistics;
import minicpbp.state.StateManager;
import minicpbp.examples.config.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.LineNumberReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static minicpbp.cp.BranchingScheme.*;
import static minicpbp.cp.Factory.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.icu.impl.Pair;

public class NLP_MLM_v1_2 {
    final static String mask_string = "[MASK]";


   public static void main(String[] args) throws Exception {
    
        System.out.println("30 novembre");
        int port = Integer.parseInt(args[1]);
        final int NUM_ITERATIONS = Integer.parseInt(args[3]);
        final double weight = Double.parseDouble(args[0]);
        final int seed = Integer.parseInt(args[4]);
        final String configArg = args.length > 5 ? args[5] : "MNREAD_MLM_Config";
        final String sentenceBuilderArg = args.length > 6 ? args[6] : "randomSentenceBuilder";
        final int oracle_top_k = args.length > 7 ? Integer.parseInt(args[7]) : 50;
        final double mask_percent = args.length > 8 ? Double.parseDouble(args[8]) : 0.2;
        final String refTypeArg = args.length > 9 ? args[9] : "DEFAULT";

        SentenceBuilder sentenceBuilder;
        switch (sentenceBuilderArg) {
            case "randomSentenceBuilder":
                sentenceBuilder = new randomSentenceBuilder();
                break;
            case "perplexitySentenceBuilder":
                sentenceBuilder = new perplexitySentenceBuilder();
                break;
            default:
                throw new IllegalArgumentException("Unknown sentence builder: " + sentenceBuilderArg);
        }

        MNREAD_MLM_Config.RefType refType;
        switch (refTypeArg) {
            case "BONLARRON":
                refType = MNREAD_MLM_Config.RefType.BONLARRON;
                break;
            case "AUTHORS":
                refType = MNREAD_MLM_Config.RefType.AUTHORS;
                break;
            case "CP_LLM":
                refType = MNREAD_MLM_Config.RefType.CP_LLM;
                break;
            case "CP":
                refType = MNREAD_MLM_Config.RefType.CP;
                break;            
            default:
                refType = MNREAD_MLM_Config.RefType.DEFAULT;
                break;
        }

         ConstraintBuilder cb;
         switch (configArg) {
             case "MNREAD_MLM_Config":
                 cb = new MNREAD_MLM_Config(refType);
                 break;
            case "CollieSent1_MLM_Config":
                cb = new CollieSent1_MLM_Config(refType);
                break;
            case "CollieSent2_MLM_Config":
                cb = new CollieSent2_MLM_Config(refType);
                break;      
            case "CollieSent3_MLM_Config":  
                cb = new CollieSent3_MLM_Config(refType);
                break;
            case "CollieSent4_MLM_Config":  
                cb = new CollieSent4_MLM_Config();
                break;
             default:
                 throw new IllegalArgumentException("Unknown config: " + configArg);
         }

        long startTime = System.currentTimeMillis();
        try {
        HttpClient client = HttpClient.newHttpClient();
        ArrayList<ScoredSentence> base_sentence = new ArrayList<>();

        ObjectMapper objectMapper = new ObjectMapper();
        String initial_sentence = "And he had no idea what to do with the fact that she was in";
        try {
            BufferedReader br = Files.newBufferedReader(Paths.get(cb.fileRef()), StandardCharsets.UTF_8);
            String line;
            int lineNumber = 0;
            while ((line = br.readLine()) != null) {
                System.out.println("Reading line " + lineNumber);
                if (lineNumber == seed) {
                    int lastComma = line.lastIndexOf(',');
                    initial_sentence = (lastComma >= 0) ? line.substring(0, lastComma).trim() : line.trim();
                    break;
                }
                lineNumber++;
            }if (initial_sentence == null) {
                throw new RuntimeException("Could not find initial sentence at line " + seed);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Error reading initial sentence from file: " + e.getMessage());
        }

        

        double ppl_init = -1.0;
        base_sentence.add(new ScoredSentence(initial_sentence, ppl_init));



        final String llm_name="modernBert";//

        List<Logging>  logs = new ArrayList<>();

        List<String> lines = Collections.emptyList();
         try {
             lines = Files.readAllLines(Paths.get("./src/main/java/minicpbp/examples/data/MNREAD/"+llm_name+"/tokenizer_dict.txt"),StandardCharsets.UTF_8);
         }
         catch (Exception e) {
             e.printStackTrace();
         }

        int token_size = Integer.parseInt(lines.get(lines.size()-1).split(":")[0]);

         String[] corrected_lines = new String[token_size];
         Arrays.fill(corrected_lines, "");
         for(int i=0;i<lines.size();i++){
             String[] line = lines.get(i).split("::");
             if(line.length>1){
                 corrected_lines[Integer.parseInt(line[0])]=line[1];
             }
         }
 
        final List<String> tokens_list = Arrays.asList(corrected_lines);
        ArrayList<String> words = new ArrayList<>();
        Map<Integer, List<Integer>> corpusDomainsSet = new HashMap<>();
        Map<Integer, Integer> corpusDomainToIndex = new HashMap<>();
        try {
            String jsonContent = new String(Files.readAllBytes(Paths.get("./src/main/java/minicpbp/examples/data/MNREAD/"+llm_name+"/corpus_tokenized_words.json")), StandardCharsets.UTF_8);
            final List<List<Integer>> parsedCorpusDomains = objectMapper.readValue(jsonContent, new TypeReference<List<List<Integer>>>() {}); 
            int j = 0;
            for (int i = 0; i < parsedCorpusDomains.size(); i++) {
                List<Integer> sublist = parsedCorpusDomains.get(i);
                if(sublist.size() != 1) continue;
                String word_string = sublist.stream().map(n -> tokens_list.get(n)).collect(Collectors.joining(""));//.strip();
                if (words.contains(word_string)) {
                    continue;
                }
                words.add(word_string.replace("##", ""));
                if (!corpusDomainsSet.containsKey(sublist.get(0)))
                    corpusDomainsSet.put(sublist.get(0), new ArrayList<>(List.of(j)));
                else
                    corpusDomainsSet.get(sublist.get(0)).add(j);
                corpusDomainToIndex.put(j, sublist.get(0));
                j++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (String w : base_sentence.get(0).getSentence().split(" ")) {
            w= " "+ w;
            if (!words.contains(w)) {
                words.add(w);
            }
        }

        List<Integer> corpusDomains = new ArrayList<>();
        for (int idx = 0; idx < words.size(); idx++) {
            corpusDomains.add(idx);
        }


        System.out.println("corpusDomains size: " + corpusDomains.size());


        int[] start_words  = new int[corpusDomains.size()];
        for(int i=0; i<corpusDomains.size(); i++){
            if(words.get(corpusDomains.get(i)).strip().length()!=0 && words.get(corpusDomains.get(i)).charAt(0)==' '){
                start_words[i]=1;
            }
        }


        String charToIntFilePath = "./src/main/java/minicpbp/examples/data/MNREAD/TimesCost_modified.json";
        Map<String, Integer> charToIntMap = new HashMap<>();
        try {
            String charToIntJson = new String(Files.readAllBytes(Paths.get(charToIntFilePath)), StandardCharsets.UTF_8);
            charToIntMap = objectMapper.readValue(charToIntJson, new TypeReference<Map<String, Integer>>() {});
        } catch (Exception e) {
            e.printStackTrace();
        }

        int[] lengthTokens = new int[corpusDomains.size()];
        int[] charNum = new int[corpusDomains.size()];
        for (int i = 0; i < corpusDomains.size(); i++) {
            int domainIndex = corpusDomains.get(i);
            String word = words.get(domainIndex);
            int charSum = 0;
            charNum[i]=word.length();
            for (char c : word.toCharArray()) {
                if(word.length()==0){
                    break;
                }
                String charStr = String.valueOf(c);
                charSum += charToIntMap.getOrDefault(charStr, 1000000);
                if (charToIntMap.getOrDefault(charStr, 1000000) == 1000000) {
                    System.err.println("Character not found in mapping: " + charStr);
                    System.err.println("Word: " + word);
                    System.err.println("Index: " + domainIndex);
                    throw new Exception("Character not found");
                }
            }
            lengthTokens[i]=charSum;
        }



        final int SENTENCE_MAX_NUMBER_TOKENS = base_sentence.get(0).getSentence().split(" ").length;
        final int ORACLE_TOP_K = oracle_top_k;
        final boolean PRINT_TRACE = false;
        final int NUM_PB = 3;
        final double w = weight;
        final int solutionLimit = 1;
        final int failureLimit = 100;

        System.out.println("Building model...");
        
        Solver cp = makeSolver();
        cp.actingOnZeroOneBelief();

        IntVar[] word_index = makeIntVarArray(cp, SENTENCE_MAX_NUMBER_TOKENS, 0, corpusDomains.size()-1);
        IntVar[] line = makeIntVarArray(cp, word_index.length, 0, 3 - 1);
        cb.build(new SolverContext(cp, corpusDomains.size(), -1, -1, charNum, lengthTokens, word_index, words, line));


        Random rand = new Random();



        String[] current_sentence= new String[1];
        String[] original_sentence= new String[1];

        IntVar[] allVars = new IntVar[word_index.length + line.length];
        System.arraycopy(word_index, 0, allVars, 0, word_index.length);
        System.arraycopy(line, 0, allVars, word_index.length, line.length);
        DFSearch dfs = makeDfs(cp, maxMarginalStrengthWithOracle(allVars," "+mask_string ,port, word_index, corpusDomainsSet, words,  w, ORACLE_TOP_K));
        final int[] l = new int[]{-1};





        ArrayList<ScoredSentence> candidateSentences = new ArrayList<>();

        dfs.onSolution(() -> {
            double perplexityScore = -1;
            String[] tokens_used = new String[SENTENCE_MAX_NUMBER_TOKENS];
            // build sentence from assigned word_index values
            String solution="";
            int[] true_tokens = new int[word_index.length];
            for (int i = 0; i < word_index.length; i++) {
                if(word_index[i].isBound()==false){
                    throw new RuntimeException("Variable not assigned at solution for index "+i);
                }
                int assigned = word_index[i].min(); // value assigned at solution
                solution += words.get(assigned);
                tokens_used[i] = words.get(assigned);
                true_tokens[i] = assigned;
            }
            solution = solution.trim();
            solution += ".";

            HttpRequest request2 = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mlm_tokenize"))
                .POST(HttpRequest.BodyPublishers.ofString(solution))
                .build();
            String response2 = client.sendAsync(request2, BodyHandlers.ofString()).thenApply(HttpResponse::body).join();
            JsonNode jsonNode2 = null;
            try {
                jsonNode2 = objectMapper.readTree(response2);
            } catch (JsonMappingException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (JsonProcessingException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            ArrayNode tokensArray = (ArrayNode) jsonNode2.get("token_ids");
            int[] tokens = new int[tokensArray.size()];
            for (int j = 0; j < tokensArray.size(); j++) {
                tokens[j] = tokensArray.get(j).asInt();
            }


            if(!solution.contains("ERROR")){
                
                if (solution.endsWith(".")) {
                    solution = solution.substring(0, solution.length() - 1);
                }
                double ppl = -1.0;
                ScoredSentence currentSentence = new ScoredSentence(solution, ppl);
                System.out.println("Solution found: " + solution + " with perplexity " + ppl);
                if (base_sentence.contains(currentSentence)) {
                    System.out.println("Duplicate sentence, skipping: " + solution);
                    return;
                }

                
                if(configArg.equals("MNREAD_MLM_Config") ){ 
                    if (cb.isValid()) {
                        Logging new_log = new Logging(solution, original_sentence[0], ppl, true_tokens, tokens_used, System.currentTimeMillis() - startTime);
                        logs.add(new_log);
                    } 
                    
                    base_sentence.add(currentSentence);
                    candidateSentences.add(currentSentence);
                    return;
                }
                else {
                    Logging new_log = new Logging(solution, original_sentence[0], ppl, true_tokens, tokens_used, System.currentTimeMillis() - startTime);
                    logs.add(new_log);
                    base_sentence.add(currentSentence);
                    candidateSentences.add(currentSentence);
                }

            }
            else {
                Logging new_log = new Logging(solution, original_sentence[0], perplexityScore, tokens, new String[tokens_used.length], System.currentTimeMillis() - startTime);
                logs.add(new_log);
            }
        });

        while (l[0] < NUM_ITERATIONS-1) {
            l[0]++;

            SearchStatistics stats=dfs.solveSubjectTo(statistics -> statistics.numberOfSolutions() >= solutionLimit || statistics.numberOfFailures() >= failureLimit, () -> {
                        if (candidateSentences.isEmpty()) {
                            candidateSentences.add(base_sentence.get(base_sentence.size() - 1));
                        }
                        current_sentence[0] = sentenceBuilder.buildSentence(candidateSentences, client, port, mask_percent, cb.getBannedIndices(word_index));
                        original_sentence[0] = current_sentence[0];
                        candidateSentences.clear();

                        int[][] neg_table = new int[base_sentence.size()][word_index.length];
                        for (ScoredSentence sentence : base_sentence){
                            String[] words_in_sentence = sentence.getSentence().split(" ");
                            for (int idx = 0; idx < word_index.length; idx++) {
                                String word = words_in_sentence[idx];
                                int word_idx = words.indexOf(" " + word);
                                neg_table[base_sentence.indexOf(sentence)][idx] = word_idx;
                            }
                        }
                        cp.post(new NegTableCT(word_index, neg_table));

                        System.out.println("Current sentence: " + current_sentence[0]);

                        String[] sentenceWords = current_sentence[0].split(" ");
                        List<Integer> masked_indexs = new ArrayList<>();
                        for (int idx = 0; idx < sentenceWords.length; idx++) {
                            if (!sentenceWords[idx].equals(mask_string)) {
                                try {
                                    word_index[idx].assign(words.indexOf(" " + sentenceWords[idx]));
                                } catch (Exception e) {
                                    System.out.println(e);
                                    System.err.println("Error assigning index " + idx + " to word " + sentenceWords[idx]);
                                    System.err.println(words.contains(" " + sentenceWords[idx]));
                                }
                            } else {
                                masked_indexs.add(idx);
                            }
                        }

                        cp.fixPoint();

                        
                        HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/mlm"))
                            .POST(HttpRequest.BodyPublishers.ofString("<s>"+current_sentence[0]+"."))
                            .build();
                        String response = client.sendAsync(request, BodyHandlers.ofString()).thenApply(HttpResponse::body).join();

                        System.out.println("Response: Received");

                        JsonNode jsonNode = null;
                        try {
                            jsonNode = objectMapper.readTree(response);
                        } catch (JsonMappingException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        } catch (JsonProcessingException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                        ObjectNode  maskedTokens = (ObjectNode) jsonNode;
                        System.out.println("Masked tokens found: " + maskedTokens.size());
                        int i=0;
                        for (Iterator<String> it = maskedTokens.fieldNames(); it.hasNext(); ) {
                            String fieldName = it.next();
                            JsonNode tok = maskedTokens.get(fieldName);
                            System.out.println("Masked token: " + tok.get("mask_word_position").asInt());

                            int z = tok.get("mask_word_position").asInt();
                            ArrayNode probsNode = (ArrayNode) tok.get("probs");
                            ArrayNode tokensNode = (ArrayNode) tok.get("tokens");
                            List<Pair<Integer, Double>> tokenScoreList = new ArrayList<>();
                            for (int idx = 0; idx < probsNode.size(); idx++) {
                                try {
                                double prob = probsNode.get(idx).asDouble();
                                int token = tokensNode.get(idx).asInt();
                                if (!corpusDomainsSet.containsKey(token)) continue;
                                if (prob < 0) continue;

                                Pair<Integer, Double> tuple = Pair.of(token, prob);
                                tokenScoreList.add(tuple);
                                } catch (Exception e) {
                                    if (PRINT_TRACE) {
                                        System.err.println(idx);
                                        System.err.println(e);
                                    }
                                }
                            }

                            int[] tokens = new int[corpusDomains.size()];
                            double[] scores = new double[corpusDomains.size()];

                            double total_score = 0;
            

                            tokenScoreList.sort((a, b) -> Double.compare(
                                b.second, a.second
                            ));


                            int added_tokens = 0;
                            for (int k = 0; k < tokenScoreList.size(); k++) {
                                if (added_tokens >= ORACLE_TOP_K) {
                                    break;
                                }
                                int token = tokenScoreList.get(k).first;
                                double score = tokenScoreList.get(k).second;
                                int[] token_indexes = corpusDomainsSet.get(token).stream().mapToInt(Integer::intValue).toArray();
                                for (int token_index : token_indexes) {
                                    if(word_index[z].contains(token_index)==true){
                                        added_tokens++;
                                    }
                                    tokens[token_index] = token_index;
                                    scores[token_index] = score;
                                    total_score += score;
                                }
                            }
                            for (int j=0; j<tokens.length; j++) {
                                double score=scores[j];
                                if (score > 0) {
                                    score /= total_score;
                                }
                            }

                            Constraint c = Factory.oracle(word_index[z], tokens, scores);

                            c.setWeight(w);
                            cp.post(c);


                        }                                                       
                    }
            );

            

           
            

        }
  
    
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "ok");
    result.put("port", port);
    result.put("num_iterations", NUM_ITERATIONS);
    result.put("num_pb", NUM_PB);
    result.put("weight", w);
    result.put("llm_name", llm_name);
    result.put("config", configArg);
    result.put("seed", seed);
    result.put("sentence_builder", sentenceBuilderArg);
    result.put("ref_type", refTypeArg);
    result.put("oracle_top_k", oracle_top_k);
    result.put("mask_percent", mask_percent);
    result.put("date", java.time.LocalDateTime.now().toString());  
    result.put("time", (System.currentTimeMillis() - startTime) / 1000.0);
    result.put("base_sentence", base_sentence.get(0));
    result.put("logs", logs);
    String OUTPUT_DIR = args.length > 3 ? args[2] : "./outputs";
    Files.createDirectories(Paths.get(OUTPUT_DIR));
    String outputFileName = OUTPUT_DIR + "/result"+configArg+ "_NLP_MLM_v1_2_" + System.currentTimeMillis()  + ".json";
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(Paths.get(outputFileName).toFile(), result);
    }
    catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error: " + e);
            // Write error to output file
            String OUTPUT_DIR = args.length > 3 ? args[2] : "./outputs";
            Files.createDirectories(Paths.get(OUTPUT_DIR));
            String outputFileName = OUTPUT_DIR + "/result"+configArg+ "_NLP_MLM_v1_2_" + System.currentTimeMillis()  + "_error.json";
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("status", "error");
            errorResult.put("config", configArg);
            errorResult.put("sentence_builder", sentenceBuilderArg);
            errorResult.put("seed", seed);  
            errorResult.put("oracle_top_k", oracle_top_k);
            errorResult.put("mask_percent", mask_percent);
            errorResult.put("date", java.time.LocalDateTime.now().toString());  
            errorResult.put("time", (System.currentTimeMillis() - startTime) / 1000.0);
            errorResult.put("error_message", e.getMessage());
            errorResult.put("exception", e.toString());
            ObjectMapper errorMapper = new ObjectMapper();
            try {
                errorMapper.writerWithDefaultPrettyPrinter().writeValue(Paths.get(outputFileName).toFile(), errorResult);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            System.exit(1);
    }
    }


    public static class Logging {

        public String sentence;
        public int[] tokens;
        public double perplexity;
        public String[] tokens_used;
        public String original_sentence;
        public long timestamp;

        public Logging() {
        }

        public Logging(String sentence, String original_sentence, double perplexityScore, int[] tokens, String[] tokens_used, long timestamp) {
            this.sentence = sentence;
            this.original_sentence = original_sentence;
            this.perplexity = perplexityScore;
            this.tokens = tokens;
            this.tokens_used = tokens_used;
            this.timestamp = timestamp;
        }
    }
}



