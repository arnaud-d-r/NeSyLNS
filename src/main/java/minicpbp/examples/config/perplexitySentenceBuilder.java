package minicpbp.examples.config;

import java.net.URI;
import java.net.http.*;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.ibm.icu.impl.Pair;

public class perplexitySentenceBuilder implements SentenceBuilder {
    private final String mask_string = "[MASK]";

    @Override
    public String buildSentence(ArrayList<ScoredSentence> bases, HttpClient client, int port, double mask_percent, ArrayList<Integer> bannedIndices) {
        ScoredSentence base = selectWeightedRandom(bases, new Random());
        System.out.println("Base sentence: " + base);
        String[] words = base.getSentence().split(" ");
        Random rand = new Random();
        int numMasks = (int) Math.ceil(mask_percent * words.length);
        List<Pair<Integer, Double>> leastToMostProbWords = new ArrayList<>();
        try {
            HttpRequest reqPpl = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mlm_perplexity"))
                .POST(HttpRequest.BodyPublishers.ofString(base.getSentence()))
                .build();
            String respPpl = client.sendAsync(reqPpl, BodyHandlers.ofString())
                .thenApply(HttpResponse::body).join();

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootPpl = objectMapper.readTree(respPpl);
            ArrayNode wordProbsNode = (ArrayNode) rootPpl.get("word_probs");
            if (wordProbsNode != null) {
                for (int i = 0; i < wordProbsNode.size(); i++) {
                    JsonNode wn = wordProbsNode.get(i);
                    int w = wn.get("word_id").asInt();
                    double p = wn.get("prob").asDouble();
                    leastToMostProbWords.add(Pair.of(w, p));
                }
                leastToMostProbWords.sort((a, b) -> Double.compare(a.second, b.second));
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        Set<Integer> maskIndices = new HashSet<>();
        maskIndices.addAll(selectIndexByProbability(leastToMostProbWords, rand, numMasks, bannedIndices));

        System.out.println("Masking indices: " + maskIndices);
        for (int idx : maskIndices) {
            words[idx] = mask_string;
        }
        return String.join(" ", words);
    }

    private static ScoredSentence selectWeightedRandom(ArrayList<ScoredSentence> sentences, Random random) {
        if (sentences.isEmpty()) return null;

        return sentences.get(random.nextInt(sentences.size()));
    }

    private static Set<Integer> selectIndexByProbability(List<Pair<Integer, Double>> probList, Random random, int numMask, ArrayList<Integer> bannedIndices) {
        if (probList.isEmpty()) return new HashSet<>();
        
        double totalProb = 0.0;
        for (Pair<Integer, Double> pair : probList) {
            if (bannedIndices != null && bannedIndices.contains(pair.first)) {
                continue;
            }
            totalProb += (1-pair.second); 
        }
        
        Set<Integer> selectedIndices = new HashSet<>();
        
        while (selectedIndices.size() < numMask && selectedIndices.size() < probList.size()) {
            double randomValue = random.nextDouble() * totalProb;
            double cumulativeProb = 0.0;
            
            for (int i = 0; i < probList.size(); i++) {
                final int wordIndex = probList.get(i).first;
                if (selectedIndices.contains(wordIndex) || (bannedIndices != null && bannedIndices.contains(wordIndex))) continue;
                
                cumulativeProb += 1.0 - probList.get(i).second;
                if (randomValue <= cumulativeProb) {
                    selectedIndices.add(wordIndex);
                    totalProb -= probList.get(i).second;
                    break;
                }
            }
        }
        
        return selectedIndices;
    }
    
}