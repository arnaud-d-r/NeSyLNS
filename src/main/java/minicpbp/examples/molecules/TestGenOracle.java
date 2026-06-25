package minicpbp.examples.molecules;

import minicpbp.engine.constraints.NegTableCT;
import minicpbp.engine.core.Constraint;
import minicpbp.engine.core.IntVar;
import minicpbp.engine.core.Solver;
import minicpbp.engine.core.Solver.PropaMode;
import minicpbp.examples.config.*;
import minicpbp.search.DFSearch;
import minicpbp.search.LDSearch;
import minicpbp.search.SearchStatistics;
import minicpbp.util.CFG;
import minicpbp.util.exception.InconsistencyException;

import static minicpbp.cp.Factory.*;
import static minicpbp.cp.BranchingScheme.*;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.Vector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.icu.impl.Pair;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class TestGenOracle {
    static String TOKEN_ADDRESS = "http://localhost:5001/mlm";
    static String PPL_ADDRESS = "http://localhost:5001/perplexity";
    static String FILE_PATH = "src/main/java/minicpbp/examples/data/Molecules/moleculeCNF_v7.txt";
    static int WORD_LENGTH = 40;
    static int MIN_MOL_WEIGHT = 2500;
    static int MAX_MOL_WEIGHT = 2750;
    static ArrayList<Logging> logs= new ArrayList<>();
    static String mask_char = "*";
    static String mask_string = "<mask>";

    public static void main(String[] args) {

        long startTime = System.currentTimeMillis();
        String architecture = args.length > 0 ? args[0] : "v1";
        float oracleWeight = Float.parseFloat(args.length > 1 ? args[1] : "1.0");
        String sentenceBuilderArg = args.length > 3 ? args[3] : "random";
        int seed = Integer.parseInt(args.length > 4 ? args[4] : "0");
        int NUM_ITERATIONS = Integer.parseInt(args.length > 5 ? args[5] : "10");
        String ref_file = args.length > 6 ? args[6] : "gpt";
        final double mask_percent = args.length > 7 ? Double.parseDouble(args[7]) : 0.2;
        final int oracle_top_k = args.length > 8 ? Integer.parseInt(args[8]) : 10;

        ArrayList<ScoredMolecule> baseMolecules = new ArrayList<>();

        MoleculeBuilder moleculeBuilder;
        switch (sentenceBuilderArg) {
            case "random":
                moleculeBuilder = new randomMoleculeBuilder();
                break;
            case "perplexity":
                moleculeBuilder = new perplexityMoleculeBuilder();
                break;
            default:
                throw new RuntimeException("Unrecognized sentence builder");
        }

        switch(ref_file) {
            case "gpt":
                ref_file = "src/main/java/minicpbp/examples/data/Molecules/gpt_ref.txt";
                break;
            case "no_gpt":
                ref_file = "src/main/java/minicpbp/examples/data/Molecules/no_gpt_ref.txt";
                break;
            default:
                System.out.println("Unrecognized reference file. The recognized files are: gpt and bert");
                throw new RuntimeException("Unrecognized reference file");
        }
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            BufferedReader br = Files.newBufferedReader(Paths.get(ref_file), StandardCharsets.UTF_8);
            String line;
            int lineNumber = 0;
            while ((line = br.readLine()) != null) {
                System.out.println("Reading line " + lineNumber);
                if (lineNumber == seed) {
                    int firstComma = line.indexOf(',');
                    String initialMolecule = (firstComma >= 0) ? line.substring(0, firstComma).trim() : line.trim();
                    initialMolecule = initialMolecule.replace("_", "");
                    int lastComma = line.lastIndexOf(',');
                    double ppl = (lastComma >= 0 ? Double.parseDouble(line.substring(lastComma + 1).trim()) : 1000000);   
                    baseMolecules.add(new ScoredMolecule(initialMolecule, ppl));
                    break;
                }
                lineNumber++;
            }
            if (baseMolecules.size() == 0) {
                throw new RuntimeException("Seed is larger than the number of lines in the reference file");
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Error reading reference file" + e);
        }






        try{
        switch (architecture) {
            case "v1":
                v1(oracleWeight, baseMolecules, startTime, moleculeBuilder, NUM_ITERATIONS, mask_percent, oracle_top_k);
                break;
            case "v1_2":
                v1_2(oracleWeight, baseMolecules, startTime, moleculeBuilder, NUM_ITERATIONS, mask_percent, oracle_top_k);
                break;
            case "v2":
                v2(oracleWeight, baseMolecules, startTime, moleculeBuilder, NUM_ITERATIONS, mask_percent, oracle_top_k);
                break;
            case "v2_noBP":
                v2_noBP(oracleWeight, baseMolecules, startTime, moleculeBuilder, NUM_ITERATIONS, mask_percent, oracle_top_k);
                break;
            default:
                System.out.println("Unrecognized method name. The recognized methods are: v1 and v2");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("port", 5001);
        result.put("num_iterations", NUM_ITERATIONS);
        result.put("num_pb", 4);
        result.put("weight", oracleWeight);
        result.put("config", architecture);
        result.put("seed", seed);
        result.put("sentence_builder", sentenceBuilderArg);
        result.put("reference_file", ref_file);
        result.put("oracle_top_k", oracle_top_k);
        result.put("mask_percent", mask_percent);
        result.put("date", java.time.LocalDateTime.now().toString());  
        result.put("time", (System.currentTimeMillis() - startTime) / 1000.0);
        result.put("base_sentence", baseMolecules.get(0));
        result.put("logs", logs);
        String OUTPUT_DIR = args.length > 3 ? args[2] : "./outputs";
        Files.createDirectories(Paths.get(OUTPUT_DIR));
        String outputFileName = OUTPUT_DIR + "/result_MOLECULE_MLM_"+architecture+"_" + System.currentTimeMillis()  + ".json";
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(Paths.get(outputFileName).toFile(), result);
        }
        catch (Exception e) {
                e.printStackTrace();
                System.out.println("Error: " + e);
                // Write error to output file
                String OUTPUT_DIR = args.length > 3 ? args[2] : "./outputs";
                String outputFileName = OUTPUT_DIR + "/result_MOLECULE_MLM_"+architecture+"_" + System.currentTimeMillis()  + "_error.json";
                Map<String, Object> errorResult = new LinkedHashMap<>();
                errorResult.put("status", "error");
                errorResult.put("config", architecture);   
                errorResult.put("sentence_builder", sentenceBuilderArg);
                errorResult.put("seed", seed);  
                errorResult.put("reference_file", ref_file);
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

    protected static class BaseModel {
        static Solver cp;
        static CFG g;
        static IntVar[] w;
        static IntVar[] tokenWeights;

        static void initialization() throws FileNotFoundException, IOException {
            cp = makeSolver(false);
            g = new CFG(FILE_PATH);
            cp.actingOnZeroOneBelief();

            System.out.println("Grammar loaded with " + g.terminalCount() + " terminals and " + g.nonTerminalCount() + " non-terminals.");

            // Creates the array of token variables
            w = makeIntVarArray(cp, WORD_LENGTH, 0, g.terminalCount()-1);
            for (int i = 0; i < WORD_LENGTH; i++) {
                w[i].setName("token_" + i);
            }

            // Creates the array of weight variables
            int minAtomWeight = Collections.min(g.tokenWeight.values());
            int maxAtomWeight = Collections.max(g.tokenWeight.values());
            tokenWeights = makeIntVarArray(cp, WORD_LENGTH, minAtomWeight, maxAtomWeight);
            for (int i = 0; i < WORD_LENGTH; i++) {
                tokenWeights[i].setName("weight_" + i);
            }
        }
    };

    /**
     * 
     * @param g The grammar, while called CFG it is in Chomsky's Normal Form
     * @param moleculeSoFar The molecule as a string
     * @return a HashMap linking each token's int id to a probability as a double
     */
private static HashMap<Integer, HashMap<Integer, Double>> getModelProbabilities(
    CFG g,
    String moleculeSoFar,
    ObjectMapper objectMapper
) {

    
    //#region Makes the request
    HttpClient client = HttpClient.newHttpClient();

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(TOKEN_ADDRESS))
        .POST(HttpRequest.BodyPublishers.ofString(moleculeSoFar))
        .build();
    String response = client.sendAsync(request, BodyHandlers.ofString())
        .thenApply(HttpResponse::body)
        .join();
    
    System.out.println("Response: Received");
    //#endregion

    //#region Parse the JSON response
    JsonNode jsonNode = null;
    try {
        jsonNode = objectMapper.readTree(response);
    } catch (JsonMappingException e) {
        e.printStackTrace();
    } catch (JsonProcessingException e) {
        e.printStackTrace();
    }
    
    ObjectNode maskedTokens = (ObjectNode) jsonNode;
    System.out.println("Masked tokens found: " + maskedTokens.size());
    
    HashMap<Integer, HashMap<Integer, Double>> positionDistributions = new HashMap<>();
    
    // Iterate through each position in the response
    for (Iterator<String> it = maskedTokens.fieldNames(); it.hasNext(); ) {
        String fieldName = it.next();
        JsonNode positionNode = maskedTokens.get(fieldName);
        
        int maskIndex = positionNode.get("mask_index").asInt();
        System.out.println("Processing mask position: " + maskIndex);
        
        ArrayNode probsNode = (ArrayNode) positionNode.get("probs");
        ArrayNode tokensNode = (ArrayNode) positionNode.get("tokens");
        
        HashMap<Integer, Double> tokenToScoreNLP = new HashMap<>();
        
        for (int idx = 0; idx < probsNode.size(); idx++) {
            try {
                double prob = probsNode.get(idx).asDouble();
                int tokenId = tokensNode.get(idx).asInt();
                
                // Check if token exists in grammar
                String tokenString = null;
                for (Map.Entry<String, Integer> entry : g.tokenEncoder.entrySet()) {
                    if (entry.getValue() == tokenId) {
                        tokenString = entry.getKey();
                        break;
                    }
                }
                
                if (tokenString == null) {
                    System.out.println("Token not in grammar: " + tokenId);
                    continue;
                }
                
                if (prob < 0) continue;
                
                tokenToScoreNLP.put(tokenId, prob);
                
            } catch (Exception e) {
                System.err.println("Error at index: " + idx);
                System.err.println(e);
            }
        }
        
        // Normalize the distribution
        HashMap<Integer, Double> normalizedScores = normalizeDistribution(tokenToScoreNLP);
        positionDistributions.put(maskIndex, normalizedScores);
    }
    //#endregion

    return positionDistributions;
}

private static HashMap<Integer, Double> normalizeDistribution(
    HashMap<Integer, Double> tokenToScoreNLP
) {
    HashMap<Integer, Double> tokenToScoreMap = new HashMap<>();
    double scoreSum = 0;
    
    for (double v : tokenToScoreNLP.values()) {
        scoreSum += v;
    }
    
    if (scoreSum == 0) return tokenToScoreMap;
    
    for (int t : tokenToScoreNLP.keySet()) {
        tokenToScoreMap.put(t, tokenToScoreNLP.get(t) / scoreSum);
    }
    
    return tokenToScoreMap;
}

    /*private static double getMoleculePerplexity(String molecule) {
        //#region Makes the request
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(PPL_ADDRESS))
            .POST(HttpRequest.BodyPublishers.ofString("<s>"+molecule))
            .build();
        String response = client.sendAsync(request, BodyHandlers.ofString()).thenApply(HttpResponse::body).join();
        //#endregion
        //#region Parse the JSON response
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = null;
        try {
            jsonNode = objectMapper.readTree(response);
        } catch (JsonMappingException e) {
            e.printStackTrace();
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return (jsonNode.get("perplexity").asDouble());
    }*/

     private static void v1(float oracleWeight, ArrayList<ScoredMolecule> baseMolecules, long startTime, MoleculeBuilder moleculeBuilder, int NUM_ITERATIONS, double mask_percent, int oracle_top_k) throws FileNotFoundException, IOException {
        try {
         //#region Base initialization
        BaseModel.initialization();
        // Create variables to shorten access
        Solver cp = BaseModel.cp;
        CFG g = BaseModel.g;
        IntVar[] w = BaseModel.w;
        IntVar[] tokenWeights = BaseModel.tokenWeights;
        //#endregion
        
        //#region Constraints
        // Smiles Validity
        GenConstraints.grammarConstraint(cp,w,g);
        GenConstraints.cycleCountingConstraint(cp,w,g,1,8);
        GenConstraints.cycleParityConstraint(cp,w,g,1,8);

        // Other constraints
        GenConstraints.moleculeWeightConstraint(cp, w, tokenWeights, makeIntVar(cp, MIN_MOL_WEIGHT, MAX_MOL_WEIGHT), g);
        //#endregion
        final int solutionLimit = 1;
        final int failureLimit = 100;

         //#region Search

        // Assign remaining tokens after base molecule to underscore
        for (int i = baseMolecules.get(0).getMolecule().length(); i < w.length; i++) {
            if (g.tokenEncoder.containsKey("_")) {
                w[i].assign(g.tokenEncoder.get("_"));
            }
        }

        DFSearch dfs = makeDfs(cp, maxMarginalStrength(w));
        final int[] iterationCount = new int[]{0};
        
        
        String[] originalMolecule = new String[1];
        String[] currentMolecule = new String[1];
        
        ArrayList<ScoredMolecule> candidateMolecules = new ArrayList<>();
        HttpClient client = HttpClient.newHttpClient();
        
        final Double[] bestScore = new Double[]{Double.MAX_VALUE};
        
        dfs.onSolution(() -> {
            // Build molecule from assigned w values
            String solution = "";
            
            for (int i = 0; i < w.length; i++) {
                if (w[i].isBound() == false) {
                    throw new RuntimeException("Variable not assigned at solution for index " + i);
                }
                int assigned = w[i].min();
                String token = g.tokenDecoder.get(assigned);
                if(token.equals("_")) break;
                solution += token;
            }
            
            solution = solution.trim();
            System.out.println("Solution found: " + solution);
            
            // Evaluate solution
            if (!solution.contains("ERROR")) {

                double score = -1;
                
                ScoredMolecule solutionMolecule = new ScoredMolecule(solution, score);
                
                System.out.println("Solution evaluated: " + solution + " with score " + score);
                
                // Check for duplicates
                if (baseMolecules.contains(solutionMolecule)) {
                    System.out.println("Duplicate molecule, skipping: " + solution);
                    return;
                }
            
                Logging newLog = new Logging(
                    solution, 
                    originalMolecule[0], 
                    score, 
                    System.currentTimeMillis() - startTime
                );
                logs.add(newLog);
                baseMolecules.add(solutionMolecule);
                candidateMolecules.add(solutionMolecule);
            
                
            } else {
                // Log error cases
                Logging newLog = new Logging(
                    solution, 
                    originalMolecule[0], 
                    -1, 
                    System.currentTimeMillis() - startTime
                );
                logs.add(newLog);
            }
        });
        
        while (iterationCount[0] < NUM_ITERATIONS - 1) {
            iterationCount[0]++;
            
            dfs.solveSubjectTo(
                statistics -> statistics.numberOfSolutions() >= solutionLimit || 
                            statistics.numberOfFailures() >= failureLimit,
                () -> {
                    Iterator<Constraint> iteratorC = cp.getConstraints().iterator();
                    while (iteratorC.hasNext()) {
                        Constraint c = iteratorC.next();
                        if (c.getName().equals("Oracle")) {
                            c.setActive(false);
                        }
                    }
                    // Select current molecule to extend
                    if (candidateMolecules.isEmpty()) {
                        candidateMolecules.add(baseMolecules.get(baseMolecules.size() - 1));
                    }
                    currentMolecule[0] = moleculeBuilder.buildMolecule(candidateMolecules, client, 5001, mask_percent);
                    originalMolecule[0] = currentMolecule[0];
                    candidateMolecules.clear();

                    int[][] neg_table = new int[baseMolecules.size()][w.length];
                    for (ScoredMolecule molecules : baseMolecules){
                        String[] words_in_sentence = Tokenizers.tokenizeV7(molecules.getMolecule()).toArray(new String[0]);
                        for (int idx = 0; idx < w.length; idx++) {
                            if(idx>=words_in_sentence.length){
                                neg_table[baseMolecules.indexOf(molecules)][idx] = g.tokenEncoder.get("_");
                                continue;
                            }
                            String token = words_in_sentence[idx];
                            int token_idx = g.tokenEncoder.get(token);
                            neg_table[baseMolecules.indexOf(molecules)][idx] = token_idx;
                        }
                    }
                    cp.post(new NegTableCT(w, neg_table));
                    
                    System.out.println("Current iteration " + iterationCount[0] + ": " + currentMolecule[0]);
                    
                    // Parse current molecule to assign known positions
                    currentMolecule[0] = currentMolecule[0].replace("<mask>", mask_char);
                    String[] tokens = Tokenizers.tokenizeV7(currentMolecule[0]).toArray(new String[0]);
                    List<Integer> maskedIndexes = new ArrayList<>();
                    
                    for (int idx = 0; idx < tokens.length; idx++) {
                        if (!tokens[idx].equals(mask_char)) {
                            try {
                                String token = tokens[idx];
                                if (g.tokenEncoder.containsKey(token)) {
                                    w[idx].assign(g.tokenEncoder.get(token));
                                }
                            } catch (Exception e) {
                                System.err.println("Error assigning index " + idx + " to token " + tokens[idx]);
                                System.err.println(g.tokenEncoder.containsKey(tokens[idx]));
                            }
                        } else {
                            maskedIndexes.add(idx);
                        }
                    }
                    
                    // Make request to get model probabilities
                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(TOKEN_ADDRESS))
                        .POST(HttpRequest.BodyPublishers.ofString(currentMolecule[0]))
                        .build();
                    String response = client.sendAsync(request, BodyHandlers.ofString())
                        .thenApply(HttpResponse::body)
                        .join();
                    
                    System.out.println("Response: Received");
                    
                    JsonNode jsonNode = null;
                    try {
                        ObjectMapper objectMapper = new ObjectMapper();
                        jsonNode = objectMapper.readTree(response);
                    } catch (JsonMappingException e) {
                        e.printStackTrace();
                    } catch (JsonProcessingException e) {
                        e.printStackTrace();
                    }
                    
                    ObjectNode positionDistributions = (ObjectNode) jsonNode;
                    System.out.println("Position distributions found: " + positionDistributions.size());
                    
                    // Process each position's distribution (unchanged logic for building oracle arrays)
                    for (Iterator<String> it = positionDistributions.fieldNames(); it.hasNext(); ) {
                        String fieldName = it.next();
                        JsonNode positionNode = positionDistributions.get(fieldName);

                        int maskIndex = positionNode.get("mask_index").asInt();
                        System.out.println("Processing mask at position: " + maskIndex);

                        int z = maskIndex;

                        ArrayNode probsNode = (ArrayNode) positionNode.get("probs");
                        ArrayNode tokensNode = (ArrayNode) positionNode.get("tokens");
                        List<Pair<Integer, Double>> tokenScoreList = new ArrayList<>();
                        
                        for (int idx = 0; idx < probsNode.size(); idx++) {
                            try {
                                double prob = probsNode.get(idx).asDouble();
                                String tokenString = tokensNode.get(idx).asText();
                                
                                if (!g.tokenEncoder.containsKey(tokenString)) {
                                    System.out.println("Token not in grammar " + tokenString);
                                    continue;
                                }
                                int token = g.tokenEncoder.get(tokenString);
                                if (prob < 0) continue;
                                
                                Pair<Integer, Double> tuple = Pair.of(token, prob);
                                tokenScoreList.add(tuple);
                                
                            } catch (Exception e) {
                                System.err.println("Error at index: " + idx);
                                System.err.println(e);
                            }
                        }

                        // Prepare arrays for oracle constraint
                        int grammarSize = g.tokenEncoder.size();
                        int[] oracleTokens = new int[grammarSize];
                        double[] oracleScores = new double[grammarSize];
                        double totalScore = 0;

                        int topK = Math.min(oracle_top_k, tokenScoreList.size());

                        for (int k = 0; k < topK; k++) {
                            int tokenId = tokenScoreList.get(k).first;
                            double score = tokenScoreList.get(k).second;

                                                        
                            if(w[z].contains(tokenId)){
                                oracleTokens[tokenId] = tokenId;
                                oracleScores[tokenId] = score;
                                totalScore += score;
                            } 
                        }

                        // Normalize scores
                        if (totalScore > 0) {
                            for (int j = 0; j < oracleTokens.length; j++) {
                                double score = oracleScores[j];
                                if (score > 0) {
                                    oracleScores[j] = score / totalScore;
                                }
                            }
                        }

                        // Post oracle constraint
                        Constraint c = oracle(w[z], oracleTokens, oracleScores);
                        c.setWeight(oracleWeight);
                        cp.post(c);
                    }

                    // Copy masked indexes set that was computed earlier
                    List<Integer> maskedIndexesCopy = new ArrayList<>(maskedIndexes);
                    System.out.println("Processing masked indexes (initial): " + maskedIndexesCopy);

                    int i = -1;
                    while (currentMolecule[0].contains(mask_char)) {
                        i++;
                        System.out.println("Processing masked index iteration: " + i);

                        // Try fixPoint to propagate oracle constraints
                        try {
                            cp.fixPoint();
                        } catch (InconsistencyException e) {
                            currentMolecule[0] = String.join("", tokens);
                            currentMolecule[0] += " ERROR";
                            break;
                        }


                        try {
                            cp.vanillaBP(3);
                        } catch (Exception e) {
                            currentMolecule[0] = String.join("", tokens);
                            currentMolecule[0] += " ERROR";
                            break;
                        }



                        if (maskedIndexesCopy.isEmpty() || maskedIndexesCopy.size() == 1) {
                            break;
                        }

                        // Choose a masked index at random from remaining
                        int chosenIndex;
                        try {
                            Random rand = new Random();
                            chosenIndex = maskedIndexesCopy.remove(rand.nextInt(maskedIndexesCopy.size()));
                        } catch (Exception e) {
                            System.out.println("Error choosing masked index");
                            break;
                        }

                        // Check that there is at least one candidate
                        try {
                            if (w[chosenIndex].maxMarginal() == 0.0) {
                                System.out.println("No valid tokens found for index " + chosenIndex);
                                currentMolecule[0] = String.join("", tokens);
                                currentMolecule[0] += " ERROR";
                                break;
                            }
                        } catch (Exception e) {
                            System.out.println("Inconsistency detected when checking marginals");
                            break;
                        }

                        // Pick value using biased wheel and assign
                        int chosenValue;
                        try {
                            chosenValue = w[chosenIndex].biasedWheelValue();
                        } catch (Exception e) {
                            System.out.println("Exception during biasedWheelValue()");
                            break;
                        }

                        System.out.println("Chosen index: " + chosenIndex + ", chosenValue: " + chosenValue +
                            ", chosen token: " + g.tokenDecoder.get(chosenValue) +
                            ", probability: " + w[chosenIndex].marginal(chosenValue));

                        try {
                            w[chosenIndex].assign(chosenValue);
                        } catch (Exception e) {
                            System.err.println("Failed to assign chosen value to w[" + chosenIndex + "]");
                            e.printStackTrace();
                            break;
                        }

                        // Update token string array and currentMolecule
                        tokens[chosenIndex] = g.tokenDecoder.get(chosenValue);
                        System.out.println("Assigning index " + chosenIndex + " to token " + tokens[chosenIndex]);
                        currentMolecule[0] = String.join("", tokens);

                    }



                }
            );
        }
    
    } catch (Exception e) {
        System.out.println(e);
        e.printStackTrace();
    }
}


    private static void v2(float oracleWeight, ArrayList<ScoredMolecule> baseMolecules, long startTime, MoleculeBuilder moleculeBuilder, int NUM_ITERATIONS, double mask_percent, int oracle_top_k) throws FileNotFoundException, IOException {
        try {
         //#region Base initialization
        BaseModel.initialization();
        // Create variables to shorten access
        Solver cp = BaseModel.cp;
        CFG g = BaseModel.g;
        IntVar[] w = BaseModel.w;
        IntVar[] tokenWeights = BaseModel.tokenWeights;
        //#endregion
        
        //#region Constraints
        // Smiles Validity
        GenConstraints.grammarConstraint(cp,w,g);
        GenConstraints.cycleCountingConstraint(cp,w,g,1,8);
        GenConstraints.cycleParityConstraint(cp,w,g,1,8);

        // Other constraints
        GenConstraints.moleculeWeightConstraint(cp, w, tokenWeights, makeIntVar(cp, MIN_MOL_WEIGHT, MAX_MOL_WEIGHT), g);
        //#endregion
        final int solutionLimit = 1;
        final int failureLimit = 100;

        DFSearch dfs = makeDfs(cp, maxMarginalStrength(w));
        final int[] iterationCount = new int[]{0};
        
        // Assign remaining tokens after base molecule to underscore
        for (int i = baseMolecules.get(0).getMolecule().length(); i < w.length; i++) {
            if (g.tokenEncoder.containsKey("_")) {
                w[i].assign(g.tokenEncoder.get("_"));
            }
        }
        
        String[] originalMolecule = new String[1];
        String[] currentMolecule = new String[1];
        
        ArrayList<ScoredMolecule> candidateMolecules = new ArrayList<>();
        HttpClient client = HttpClient.newHttpClient();
        
        final Double[] bestScore = new Double[]{Double.MAX_VALUE};
        
        dfs.onSolution(() -> {
            // Build molecule from assigned w values
            String solution = "";
            
            for (int i = 0; i < w.length; i++) {
                if (w[i].isBound() == false) {
                    throw new RuntimeException("Variable not assigned at solution for index " + i);
                }
                int assigned = w[i].min();
                String token = g.tokenDecoder.get(assigned);
                if(token.equals("_")) break;
                solution += token;
            }
            
            solution = solution.trim();
            System.out.println("Solution found: " + solution);
            
            // Evaluate solution
            if (!solution.contains("ERROR")) {

                double score = -1;
                
                ScoredMolecule solutionMolecule = new ScoredMolecule(solution, score);
                
                System.out.println("Solution evaluated: " + solution + " with score " + score);
                
                // Check for duplicates
                if (baseMolecules.contains(solutionMolecule)) {
                    System.out.println("Duplicate molecule, skipping: " + solution);
                    return;
                }
            
                Logging newLog = new Logging(
                    solution, 
                    originalMolecule[0], 
                    score, 
                    System.currentTimeMillis() - startTime
                );
                logs.add(newLog);
                baseMolecules.add(solutionMolecule);
                candidateMolecules.add(solutionMolecule);
                
                
            } else {
                // Log error cases
                Logging newLog = new Logging(
                    solution, 
                    originalMolecule[0], 
                    -1, 
                    System.currentTimeMillis() - startTime
                );
                logs.add(newLog);
            }
        });
        
        while (iterationCount[0] < NUM_ITERATIONS - 1) {
            iterationCount[0]++;
            
            dfs.solveSubjectTo(
                statistics -> statistics.numberOfSolutions() >= solutionLimit || 
                            statistics.numberOfFailures() >= failureLimit,
                () -> {
                    Iterator<Constraint> iteratorC = cp.getConstraints().iterator();
                    while (iteratorC.hasNext()) {
                        Constraint c = iteratorC.next();
                        if (c.getName().equals("Oracle")) {
                            c.setActive(false);
                        }
                    }
                    // Select current molecule to extend
                    if (candidateMolecules.isEmpty()) {
                        candidateMolecules.add(baseMolecules.get(baseMolecules.size() - 1));
                    }
                    currentMolecule[0] = moleculeBuilder.buildMolecule(candidateMolecules, client, 5001, mask_percent);
                    originalMolecule[0] = currentMolecule[0];
                    candidateMolecules.clear();

                    int[][] neg_table = new int[baseMolecules.size()][w.length];
                    for (ScoredMolecule molecules : baseMolecules){
                        String[] words_in_sentence = Tokenizers.tokenizeV7(molecules.getMolecule()).toArray(new String[0]);
                        for (int idx = 0; idx < w.length; idx++) {
                            if(idx>=words_in_sentence.length){
                                neg_table[baseMolecules.indexOf(molecules)][idx] = g.tokenEncoder.get("_");
                                continue;
                            }
                            String token = words_in_sentence[idx];
                            int token_idx = g.tokenEncoder.get(token);
                            neg_table[baseMolecules.indexOf(molecules)][idx] = token_idx;
                        }
                    }
                    cp.post(new NegTableCT(w, neg_table));
                    
                    System.out.println("Current iteration " + iterationCount[0] + ": " + currentMolecule[0]);
                    
                    // Parse current molecule to assign known positions
                    currentMolecule[0] = currentMolecule[0].replace("<mask>", mask_char);
                    String[] tokens = Tokenizers.tokenizeV7(currentMolecule[0]).toArray(new String[0]);
                    List<Integer> maskedIndexes = new ArrayList<>();
                    
                    for (int idx = 0; idx < tokens.length; idx++) {
                        if (!tokens[idx].equals(mask_char)) {
                            try {
                                String token = tokens[idx];
                                if (g.tokenEncoder.containsKey(token)) {
                                    w[idx].assign(g.tokenEncoder.get(token));
                                }
                            } catch (Exception e) {
                                System.err.println("Error assigning index " + idx + " to token " + tokens[idx]);
                                System.err.println(g.tokenEncoder.containsKey(tokens[idx]));
                            }
                        } else {
                            maskedIndexes.add(idx);
                        }
                    }
                    cp.fixPoint();
                    
                    // Make request to get model probabilities
                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(TOKEN_ADDRESS))
                        .POST(HttpRequest.BodyPublishers.ofString(currentMolecule[0]))
                        .build();
                    String response = client.sendAsync(request, BodyHandlers.ofString())
                        .thenApply(HttpResponse::body)
                        .join();
                    
                    System.out.println("Response: Received");
                    
                    JsonNode jsonNode = null;
                    try {
                        ObjectMapper objectMapper = new ObjectMapper();
                        jsonNode = objectMapper.readTree(response);
                    } catch (JsonMappingException e) {
                        e.printStackTrace();
                    } catch (JsonProcessingException e) {
                        e.printStackTrace();
                    }
                    
                    ObjectNode positionDistributions = (ObjectNode) jsonNode;
                    System.out.println("Position distributions found: " + positionDistributions.size());
                    
                    // Process each position's distribution
                    for (Iterator<String> it = positionDistributions.fieldNames(); it.hasNext(); ) {
                        String fieldName = it.next();
                        JsonNode positionNode = positionDistributions.get(fieldName);
                        
                        int maskIndex = positionNode.get("mask_index").asInt();
                        
                        System.out.println("Processing mask at position: " + maskIndex);
                        
                        
                        int z = maskIndex;
                        
                        ArrayNode probsNode = (ArrayNode) positionNode.get("probs");
                        ArrayNode tokensNode = (ArrayNode) positionNode.get("tokens");
                        List<Pair<Integer, Double>> tokenScoreList = new ArrayList<>();
                        
                        for (int idx = 0; idx < probsNode.size(); idx++) {
                            try {
                                double prob = probsNode.get(idx).asDouble();
                                String tokenString = tokensNode.get(idx).asText();
                                
                                if (!g.tokenEncoder.containsKey(tokenString)) {
                                    System.out.println("Token not in grammar " + tokenString);
                                    continue;
                                }
                                int token = g.tokenEncoder.get(tokenString);
                                if (prob < 0) continue;
                                
                                Pair<Integer, Double> tuple = Pair.of(token, prob);
                                tokenScoreList.add(tuple);
                                
                            } catch (Exception e) {
                                System.err.println("Error at index: " + idx);
                                System.err.println(e);
                            }
                        }
                        
                        // Prepare arrays for oracle constraint
                        int[] oracleTokens = new int[g.tokenEncoder.size()];
                        double[] oracleScores = new double[g.tokenEncoder.size()];
                        double totalScore = 0;
                        
                        int added_tokens = 0;
                        
                        for (int k = 0; k < tokenScoreList.size(); k++) {
                            if (added_tokens >= oracle_top_k) {
                                break;
                            }
                            int tokenId = tokenScoreList.get(k).first;
                            double score = tokenScoreList.get(k).second;
                                                        
                            if(w[z].contains(tokenId)){
                                added_tokens += 1;
                                oracleTokens[tokenId] = tokenId;
                                oracleScores[tokenId] = score;
                                totalScore += score;
                            } 
                        }
                        
                        // Normalize scores
                        for (int j = 0; j < oracleTokens.length; j++) {
                            double score = oracleScores[j];
                            if (score > 0) {
                                score /= totalScore;
                                oracleScores[j] = score;
                            }
                        }
                        
                        // Post oracle constraint
                        Constraint c = oracle(w[z], oracleTokens, oracleScores);
                        c.setWeight(oracleWeight);
                        cp.post(c);
                    }
                }
            );
        }
    
    } catch (Exception e) {
        System.out.println(e);
        e.printStackTrace();
    }
}

    private static void v2_noBP(float oracleWeight, ArrayList<ScoredMolecule> baseMolecules, long startTime, MoleculeBuilder moleculeBuilder, int NUM_ITERATIONS, double mask_percent, int oracle_top_k) throws FileNotFoundException, IOException {
        try {
         //#region Base initialization
        BaseModel.initialization();
        // Create variables to shorten access
        Solver cp = BaseModel.cp;
        CFG g = BaseModel.g;
        IntVar[] w = BaseModel.w;
        IntVar[] tokenWeights = BaseModel.tokenWeights;
        //#endregion
        cp.setMode(PropaMode.SP);
        
        //#region Constraints
        // Smiles Validity
        GenConstraints.grammarConstraint(cp,w,g);
        GenConstraints.cycleCountingConstraint(cp,w,g,1,8);
        GenConstraints.cycleParityConstraint(cp,w,g,1,8);

        // Other constraints
        GenConstraints.moleculeWeightConstraint(cp, w, tokenWeights, makeIntVar(cp, MIN_MOL_WEIGHT, MAX_MOL_WEIGHT), g);
        //#endregion
        final int solutionLimit = 1;
        final int failureLimit = 100;

        DFSearch dfs = makeDfs(cp, domWdegWithOracle(w, mask_string, TOKEN_ADDRESS, g.tokenEncoder, g.tokenDecoder));
        final int[] iterationCount = new int[]{0};
        
        // Assign remaining tokens after base molecule to underscore
        for (int i = baseMolecules.get(0).getMolecule().length(); i < w.length; i++) {
            if (g.tokenEncoder.containsKey("_")) {
                w[i].assign(g.tokenEncoder.get("_"));
            }
        }
        
        String[] originalMolecule = new String[1];
        String[] currentMolecule = new String[1];
        
        ArrayList<ScoredMolecule> candidateMolecules = new ArrayList<>();
        HttpClient client = HttpClient.newHttpClient();
        
        final Double[] bestScore = new Double[]{Double.MAX_VALUE};
        
        dfs.onSolution(() -> {
            // Build molecule from assigned w values
            String solution = "";
            
            for (int i = 0; i < w.length; i++) {
                if (w[i].isBound() == false) {
                    throw new RuntimeException("Variable not assigned at solution for index " + i);
                }
                int assigned = w[i].min();
                String token = g.tokenDecoder.get(assigned);
                if(token.equals("_")) break;
                solution += token;
            }
            
            solution = solution.trim();
            System.out.println("Solution found: " + solution);
            
            // Evaluate solution
            if (!solution.contains("ERROR")) {

                double score = -1;
                
                ScoredMolecule solutionMolecule = new ScoredMolecule(solution, score);
                
                System.out.println("Solution evaluated: " + solution + " with score " + score);
                
                // Check for duplicates
                if (baseMolecules.contains(solutionMolecule)) {
                    System.out.println("Duplicate molecule, skipping: " + solution);
                    return;
                }
              

                Logging newLog = new Logging(
                    solution, 
                    originalMolecule[0], 
                    score, 
                    System.currentTimeMillis() - startTime
                );
                logs.add(newLog);
                baseMolecules.add(solutionMolecule);
                candidateMolecules.add(solutionMolecule);
            
                
            } else {
                // Log error cases
                Logging newLog = new Logging(
                    solution, 
                    originalMolecule[0], 
                    -1, 
                    System.currentTimeMillis() - startTime
                );
                logs.add(newLog);
            }
        });
        
        while (iterationCount[0] < NUM_ITERATIONS - 1) {
            iterationCount[0]++;
            
            dfs.solveSubjectTo(
                statistics -> statistics.numberOfSolutions() >= solutionLimit || 
                            statistics.numberOfFailures() >= failureLimit,
                () -> {
                    Iterator<Constraint> iteratorC = cp.getConstraints().iterator();
                    while (iteratorC.hasNext()) {
                        Constraint c = iteratorC.next();
                        if (c.getName().equals("Oracle")) {
                            c.setActive(false);
                        }
                    }
                    // Select current molecule to extend
                    if (candidateMolecules.isEmpty()) {
                        candidateMolecules.add(baseMolecules.get(baseMolecules.size() - 1));
                    }
                    currentMolecule[0] = moleculeBuilder.buildMolecule(candidateMolecules, client, 5001, mask_percent);
                    originalMolecule[0] = currentMolecule[0];
                    candidateMolecules.clear();

                    int[][] neg_table = new int[baseMolecules.size()][w.length];
                    for (ScoredMolecule molecules : baseMolecules){
                        String[] words_in_sentence = Tokenizers.tokenizeV7(molecules.getMolecule()).toArray(new String[0]);
                        for (int idx = 0; idx < w.length; idx++) {
                            if(idx>=words_in_sentence.length){
                                neg_table[baseMolecules.indexOf(molecules)][idx] = g.tokenEncoder.get("_");
                                continue;
                            }
                            String token = words_in_sentence[idx];
                            int token_idx = g.tokenEncoder.get(token);
                            neg_table[baseMolecules.indexOf(molecules)][idx] = token_idx;
                        }
                    }
                    cp.post(new NegTableCT(w, neg_table));
                    
                    System.out.println("Current iteration " + iterationCount[0] + ": " + currentMolecule[0]);
                    
                    // Parse current molecule to assign known positions
                    currentMolecule[0] = currentMolecule[0].replace("<mask>", mask_char);
                    String[] tokens = Tokenizers.tokenizeV7(currentMolecule[0]).toArray(new String[0]);
                    List<Integer> maskedIndexes = new ArrayList<>();
                    
                    for (int idx = 0; idx < tokens.length; idx++) {
                        if (!tokens[idx].equals(mask_char)) {
                            try {
                                String token = tokens[idx];
                                if (g.tokenEncoder.containsKey(token)) {
                                    w[idx].assign(g.tokenEncoder.get(token));
                                }
                            } catch (Exception e) {
                                System.err.println("Error assigning index " + idx + " to token " + tokens[idx]);
                                System.err.println(g.tokenEncoder.containsKey(tokens[idx]));
                            }
                        } else {
                            maskedIndexes.add(idx);
                        }
                    }
                    
                    
                }
            );
        }
    
    } catch (Exception e) {
        System.out.println(e);
        e.printStackTrace();
    }
}
    private static void v1_2(float oracleWeight, ArrayList<ScoredMolecule> baseMolecules, long startTime, MoleculeBuilder moleculeBuilder, int NUM_ITERATIONS, double mask_percent, int oracle_top_k) throws FileNotFoundException, IOException {
        try {
        System.out.println(System.currentTimeMillis() - startTime + " ms: Starting v1.2");
         //#region Base initialization
        BaseModel.initialization();
        // Create variables to shorten access
        Solver cp = BaseModel.cp;
        CFG g = BaseModel.g;
        IntVar[] w = BaseModel.w;
        IntVar[] tokenWeights = BaseModel.tokenWeights;
        //#endregion
        
        //#region Constraints
        // Smiles Validity
        GenConstraints.grammarConstraint(cp,w,g);
        GenConstraints.cycleCountingConstraint(cp,w,g,1,8);
        GenConstraints.cycleParityConstraint(cp,w,g,1,8);

        // Other constraints
        GenConstraints.moleculeWeightConstraint(cp, w, tokenWeights, makeIntVar(cp, MIN_MOL_WEIGHT, MAX_MOL_WEIGHT), g);
        //#endregion
        final int solutionLimit = 1;
        final int failureLimit = 100;

        System.out.println(System.currentTimeMillis() - startTime + " ms: Starting DFS");

        DFSearch dfs = makeDfs(cp, maxMarginalStrengthWithOracle(w, mask_string, TOKEN_ADDRESS, g.tokenEncoder, g.tokenDecoder , w, oracleWeight, oracle_top_k));
        final int[] iterationCount = new int[]{0};

        System.out.println(System.currentTimeMillis() - startTime + " ms: Assigning base molecule");
        
        // Assign remaining tokens after base molecule to underscore
        for (int i = baseMolecules.get(0).getMolecule().length(); i < w.length; i++) {
            if (g.tokenEncoder.containsKey("_")) {
                w[i].assign(g.tokenEncoder.get("_"));
            }
        }
        
        String[] originalMolecule = new String[1];
        String[] currentMolecule = new String[1];
        
        ArrayList<ScoredMolecule> candidateMolecules = new ArrayList<>();
        HttpClient client = HttpClient.newHttpClient();
        
        final Double[] bestScore = new Double[]{Double.MAX_VALUE};
        
        dfs.onSolution(() -> {
            // Build molecule from assigned w values
            String solution = "";
            
            for (int i = 0; i < w.length; i++) {
                if (w[i].isBound() == false) {
                    throw new RuntimeException("Variable not assigned at solution for index " + i);
                }
                int assigned = w[i].min();
                String token = g.tokenDecoder.get(assigned);
                if(token.equals("_")) break;
                solution += token;
            }
            
            solution = solution.trim();
            System.out.println("Solution found: " + solution);
            
            // Evaluate solution
            if (!solution.contains("ERROR")) {

                double score = -1;
                
                ScoredMolecule solutionMolecule = new ScoredMolecule(solution, score);
                
                System.out.println("Solution evaluated: " + solution + " with score " + score);
                
                // Check for duplicates
                if (baseMolecules.contains(solutionMolecule)) {
                    System.out.println("Duplicate molecule, skipping: " + solution);
                    return;
                }
              
                Logging newLog = new Logging(
                    solution, 
                    originalMolecule[0], 
                    score, 
                    System.currentTimeMillis() - startTime
                );
                logs.add(newLog);
                baseMolecules.add(solutionMolecule);
                candidateMolecules.add(solutionMolecule);

                
            } else {
                // Log error cases
                Logging newLog = new Logging(
                    solution, 
                    originalMolecule[0], 
                    -1, 
                    System.currentTimeMillis() - startTime
                );
                logs.add(newLog);
            }
        });
        
        while (iterationCount[0] < NUM_ITERATIONS - 1) {
            iterationCount[0]++;

            System.out.println(System.currentTimeMillis() - startTime + " ms: Starting iteration " + iterationCount[0]);
            
            dfs.solveSubjectTo(
                statistics -> statistics.numberOfSolutions() >= solutionLimit || 
                            statistics.numberOfFailures() >= failureLimit,
                () -> {
                    Iterator<Constraint> iteratorC = cp.getConstraints().iterator();
                    while (iteratorC.hasNext()) {
                        Constraint c = iteratorC.next();
                        if (c.getName().equals("Oracle")) {
                            c.setActive(false);
                        }
                    }
                    // Select current molecule to extend
                    if (candidateMolecules.isEmpty()) {
                        candidateMolecules.add(baseMolecules.get(baseMolecules.size() - 1));
                    }
                    currentMolecule[0] = moleculeBuilder.buildMolecule(candidateMolecules, client, 5001, mask_percent);
                    originalMolecule[0] = currentMolecule[0];
                    candidateMolecules.clear();

                    int[][] neg_table = new int[baseMolecules.size()][w.length];
                    for (ScoredMolecule molecules : baseMolecules){
                        String[] words_in_sentence = Tokenizers.tokenizeV7(molecules.getMolecule()).toArray(new String[0]);
                        for (int idx = 0; idx < w.length; idx++) {
                            if(idx>=words_in_sentence.length){
                                neg_table[baseMolecules.indexOf(molecules)][idx] = g.tokenEncoder.get("_");
                                continue;
                            }
                            String token = words_in_sentence[idx];
                            int token_idx = g.tokenEncoder.get(token);
                            neg_table[baseMolecules.indexOf(molecules)][idx] = token_idx;
                        }
                    }
                    cp.post(new NegTableCT(w, neg_table));
                    
                    System.out.println("Current iteration " + iterationCount[0] + ": " + currentMolecule[0]);
                    
                    // Parse current molecule to assign known positions
                    currentMolecule[0] = currentMolecule[0].replace("<mask>", mask_char);
                    String[] tokens = Tokenizers.tokenizeV7(currentMolecule[0]).toArray(new String[0]);
                    List<Integer> maskedIndexes = new ArrayList<>();
                    
                    for (int idx = 0; idx < tokens.length; idx++) {
                        if (!tokens[idx].equals(mask_char)) {
                            try {
                                String token = tokens[idx];
                                if (g.tokenEncoder.containsKey(token)) {
                                    w[idx].assign(g.tokenEncoder.get(token));
                                }
                            } catch (Exception e) {
                                System.err.println("Error assigning index " + idx + " to token " + tokens[idx]);
                                System.err.println(g.tokenEncoder.containsKey(tokens[idx]));
                            }
                        } else {
                            maskedIndexes.add(idx);
                        }
                    }
                    cp.fixPoint();

                                        // Make request to get model probabilities
                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(TOKEN_ADDRESS))
                        .POST(HttpRequest.BodyPublishers.ofString(currentMolecule[0]))
                        .build();
                    String response = client.sendAsync(request, BodyHandlers.ofString())
                        .thenApply(HttpResponse::body)
                        .join();
                    
                    System.out.println("Response: Received");
                    
                    JsonNode jsonNode = null;
                    try {
                        ObjectMapper objectMapper = new ObjectMapper();
                        jsonNode = objectMapper.readTree(response);
                    } catch (JsonMappingException e) {
                        e.printStackTrace();
                    } catch (JsonProcessingException e) {
                        e.printStackTrace();
                    }
                    
                    ObjectNode positionDistributions = (ObjectNode) jsonNode;
                    System.out.println("Position distributions found: " + positionDistributions.size());
                    
                    // Process each position's distribution
                    for (Iterator<String> it = positionDistributions.fieldNames(); it.hasNext(); ) {
                        String fieldName = it.next();
                        JsonNode positionNode = positionDistributions.get(fieldName);
                        
                        int maskIndex = positionNode.get("mask_index").asInt();
                        
                        System.out.println("Processing mask at position: " + maskIndex);
                        
                        
                        int z = maskIndex;
                        
                        ArrayNode probsNode = (ArrayNode) positionNode.get("probs");
                        ArrayNode tokensNode = (ArrayNode) positionNode.get("tokens");
                        List<Pair<Integer, Double>> tokenScoreList = new ArrayList<>();
                        
                        for (int idx = 0; idx < probsNode.size(); idx++) {
                            try {
                                double prob = probsNode.get(idx).asDouble();
                                String tokenString = tokensNode.get(idx).asText();
                                
                                if (!g.tokenEncoder.containsKey(tokenString)) {
                                    System.out.println("Token not in grammar " + tokenString);
                                    continue;
                                }
                                int token = g.tokenEncoder.get(tokenString);
                                if (prob < 0) continue;
                                
                                Pair<Integer, Double> tuple = Pair.of(token, prob);
                                tokenScoreList.add(tuple);
                                
                            } catch (Exception e) {
                                System.err.println("Error at index: " + idx);
                                System.err.println(e);
                            }
                        }
                        
                        // Prepare arrays for oracle constraint
                        int[] oracleTokens = new int[g.tokenEncoder.size()];
                        double[] oracleScores = new double[g.tokenEncoder.size()];
                        double totalScore = 0;
                        
                        int added_tokens = 0;
                        
                        for (int k = 0; k < tokenScoreList.size(); k++) {
                            if (added_tokens >= oracle_top_k) {
                                break;
                            }
                            int tokenId = tokenScoreList.get(k).first;
                            double score = tokenScoreList.get(k).second;
                            
                            
                            if(w[z].contains(tokenId)){
                                added_tokens += 1;
                                oracleTokens[tokenId] = tokenId;
                                oracleScores[tokenId] = score;
                                totalScore += score;
                            } 
                        }
                        
                        // Normalize scores
                        for (int j = 0; j < oracleTokens.length; j++) {
                            double score = oracleScores[j];
                            if (score > 0) {
                                score /= totalScore;
                                oracleScores[j] = score;
                            }
                        }
                        
                        // Post oracle constraint
                        Constraint c = oracle(w[z], oracleTokens, oracleScores);
                        c.setWeight(oracleWeight);
                        cp.post(c);
                    }
                    
                }
            );
        }
    
    } catch (Exception e) {
        System.out.println(e);
        e.printStackTrace();
    }
}

    
     static class Logging {

        public String sentence;
        public double perplexity;
        public String original_sentence;
        public long timestamp;

        public Logging() {
        }

        public Logging(String sentence, String original_sentence, double perplexityScore, long timestamp) {
            this.sentence = sentence;
            this.original_sentence = original_sentence;
            this.perplexity = perplexityScore;
            this.timestamp = timestamp;
        }
    }

}

