package minicpbp.examples.config;

import java.net.URI;
import java.net.http.*;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.ibm.icu.impl.Pair;

public class perplexityMoleculeBuilder implements MoleculeBuilder {
    private final String mask_string = "<mask>";

    @Override
    public String buildMolecule(ArrayList<ScoredMolecule> bases, HttpClient client, int port, double mask_percent) {
        ScoredMolecule base = selectWeightedRandom(bases, new Random(), 0.8);
        System.out.println("Base molecule: " + base);
        
        String molecule = base.getMolecule();
        Random rand = new Random();
        
        // Determine number of positions to mask (e.g., 20-30% of molecule length)
        int numMasks = (int) Math.ceil(mask_percent * molecule.length());
        
        List<Pair<Integer, Double>> leastToMostProbChars = new ArrayList<>();
        
        try {
            // Request character-level perplexity from the server
            HttpRequest reqPpl = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mlm_perplexity"))
                .POST(HttpRequest.BodyPublishers.ofString(molecule))
                .build();
            String respPpl = client.sendAsync(reqPpl, BodyHandlers.ofString())
                .thenApply(HttpResponse::body).join();

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootPpl = objectMapper.readTree(respPpl);
            ArrayNode charProbsNode = (ArrayNode) rootPpl.get("token_probs");
            
            if (charProbsNode != null) {
                for (int i = 0; i < charProbsNode.size(); i++) {
                    JsonNode cn = charProbsNode.get(i);
                    int charIdx = cn.get("index").asInt();
                    double prob = cn.get("prob").asDouble();
                    leastToMostProbChars.add(Pair.of(charIdx, prob));
                }
                // Sort by probability (least to most probable)
                leastToMostProbChars.sort((a, b) -> Double.compare(a.second, b.second));
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            // Fallback to random masking if perplexity request fails
            throw new RuntimeException("Failed to get character perplexities", ex);
        }
        
        Set<Integer> maskIndices = new HashSet<>();
        if (!leastToMostProbChars.isEmpty()) {
            maskIndices.addAll(selectIndexByProbability(leastToMostProbChars, rand, numMasks));
        } else {
            throw new RuntimeException("No character probabilities available for masking.");
        }

        System.out.println("Masking " + maskIndices.size() + " positions: " + maskIndices);
        
        // Build the masked molecule
        StringBuilder maskedMolecule = new StringBuilder();
        for (int i = 0; i < molecule.length(); i++) {
            if (maskIndices.contains(i)) {
                maskedMolecule.append(mask_string);
            } else {
                maskedMolecule.append(molecule.charAt(i));
            }
        }
        
        return maskedMolecule.toString();
    }




    public static ScoredMolecule selectWeightedRandom(
            ArrayList<ScoredMolecule> molecules, 
            Random random, 
            double temperature) {
        
        if (molecules.isEmpty()) return null;

        double totalWeight = 0.0;
        
        for (ScoredMolecule mol : molecules) {
            double weight = Math.exp(-mol.getScore() / temperature);
            mol.setWeight(weight);
            totalWeight += weight;
        }
        
        for (ScoredMolecule mol : molecules) {
            mol.setWeight(mol.getWeight() / totalWeight);
        }
        
        double randomValue = random.nextDouble();
        double cumulativeWeight = 0.0;
        
        for (ScoredMolecule mol : molecules) {
            cumulativeWeight += mol.getWeight();
            if (randomValue <= cumulativeWeight) {
                return mol;
            }
        }
        
        return molecules.get(molecules.size() - 1);
    }

    /**
     * Select character indices to mask based on their probabilities
     * Lower probability characters are more likely to be selected
     */
    private static Set<Integer> selectIndexByProbability(
            List<Pair<Integer, Double>> probList, 
            Random random, 
            int numMask) {
        
        if (probList.isEmpty()) return new HashSet<>();
        
        // Calculate total inverse probability (for weighting toward low-prob chars)
        double totalInverseProb = 0.0;
        for (Pair<Integer, Double> pair : probList) {
            totalInverseProb += (1.0 - pair.second);
        }
        
        Set<Integer> selectedIndices = new HashSet<>();
        Set<Integer> usedListIndices = new HashSet<>();
        
        while (selectedIndices.size() < numMask && usedListIndices.size() < probList.size()) {
            double randomValue = random.nextDouble() * totalInverseProb;
            double cumulativeProb = 0.0;
            
            for (int i = 0; i < probList.size(); i++) {
                if (usedListIndices.contains(i)) continue;
                
                double inverseProb = 1.0 - probList.get(i).second;
                cumulativeProb += inverseProb;
                
                if (randomValue <= cumulativeProb) {
                    int charIndex = probList.get(i).first;
                    selectedIndices.add(charIndex);
                    usedListIndices.add(i);
                    totalInverseProb -= inverseProb;
                    break;
                }
            }
        }
        
        return selectedIndices;
    }
}
