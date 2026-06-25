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
 * mini-cpbp, replacing classic propagation by belief propagation 
 * Copyright (c)  2019. by Gilles Pesant
 */

package minicpbp.util;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Vector;

import minicpbp.util.io.InputReader;

import java.util.HashSet;
import java.util.Set;

/**
 * Context-Free Grammar for Grammar constraint
 * (code adapted from that of Claude-Guy Quimper)
 *
 * Grammar is read in the following order: terminalCount, nonTerminalCount, length1productionCount, length2productionCount...
 * ... for each length-1 production: left, right symbol; for each length-2 production: left, right symbols
 */

public class CFG {
    private Production[] productions;
    private int productionCount;

    private Production[] length1productions, length2productions;
    private int length1productionCount, length2productionCount;
	private Set<Production>[] lhs1prod, lhs2prod; // productions indexed by left-hand-side
	private Set<Production>[] rhs2prod; // productions indexed by 1st nonterminal on right-hand-side

    private int terminalCount; // Any integer c s.t. 0 <= c < terminalCount is a terminal
    private int nonTerminalCount; // Start symbol is terminalCount
	public HashMap<Integer, String> tokenDecoder;
    public HashMap<String, Integer> tokenEncoder;

    public HashMap<String, Integer> tokenWeight = new HashMap<String, Integer>() {{
        put("C", 140);
        put("c", 130);
        put("N", 150);
        put("n", 140);
        put("O", 160);
        put("o", 160);
        put("S", 321);
        put("s", 321);
        put("F", 180);
        put("Cl", 345);
        put("Br", 789);
        put("I", 1259);
        put("=", -20);
        put("#", -40);
        put("+", 10);
        put("1", -10);
        put("2", -10);
        put("3", -10);
        put("4", -10);
        put("5", -10);
        put("6", -10);
        put("7", -10);
        put("8", -10);
        put("9", -10);
        put("%10", -10);
        put("%11", -10);
        put("%12", -10);
        put("%13", -10);
        put("%14", -10);
        put("%15", -10);
        put("%16", -10);
        put("%17", -10);
        put("%18", -10);
        put("%19", -10);
    }};

    public CFG(String filePath) throws FileNotFoundException, IOException {
        BufferedReader reader = new BufferedReader(new FileReader(filePath));
        HashMap<String, Vector<String[]>> grammar = new HashMap<>();
        
        if (!reader.ready()) {
            reader.close();
            throw new IOException();
        }
        
        // Sets up the starting symbol
        String line = reader.readLine();
        String[] production = line.split(" -> ");
        String left = production[0];
        String startSymbol = left;
        String[] rightTokens = production[1].split(" ");
        grammar.putIfAbsent(left, new Vector<>());
        grammar.get(left).add(rightTokens);

        // Reads until done
        while(reader.ready()) {
            line = reader.readLine();
            production = line.split(" -> ");
            left = production[0];
            rightTokens = production[1].split(" ");
            grammar.putIfAbsent(left, new Vector<>());
            grammar.get(left).add(rightTokens);
        }
        reader.close();

        // Creates the terminals and nonTerminals sets
        Set<String> nonTerminals = new HashSet<>();
        Set<String> terminals = new HashSet<>();
        productionCount = 0;
        for(String key : grammar.keySet()) {
            nonTerminals.add(key);
            for(String[] tokens : grammar.get(key)) {
                productionCount++;
                for (String token : tokens) {
                    (grammar.keySet().contains(token) ? nonTerminals : terminals).add(token);
                }
            }
        }

        // Creates the tokenEncoder and Decoder
        terminalCount = terminals.size();
        nonTerminalCount = nonTerminals.size();
        tokenEncoder = new HashMap<>();
        tokenDecoder = new HashMap<>();
        // Terminals go first, from 0 to terminals.size()
        for(String token : terminals) {
            tokenEncoder.put(token, tokenEncoder.size());
            tokenDecoder.put(tokenDecoder.size(), token);
        }
        // Puts the start symbol at the start of the nonTerminals
        tokenEncoder.put(startSymbol, tokenEncoder.size());
        tokenDecoder.put(tokenDecoder.size(), startSymbol);
        // Adds the rest of the nonTerminals
        for(String token : nonTerminals) {
            if (!tokenEncoder.containsKey(token)) {
                tokenEncoder.put(token, tokenEncoder.size());
                tokenDecoder.put(tokenDecoder.size(), token);
            }
        }

        // Encodes the productions so they use the numbers
        productions = new Production[productionCount];
        int processedProductions = 0;
        for(String key : grammar.keySet()) {
            for(String[] tokens : grammar.get(key)) {
                int[] numTokens = new int[tokens.length];
                for (int i = 0; i < tokens.length; i++) {
                    numTokens[i] = tokenEncoder.get(tokens[i]);
                }
                Production prod = new Production(tokenEncoder.get(key), numTokens);
                productions[processedProductions] = prod;
                processedProductions++;
            }
        }


		lhs1prod = new Set[nonTerminalCount];
		lhs2prod = new Set[nonTerminalCount];
		rhs2prod = new Set[nonTerminalCount];
		for (int i = 0; i < nonTerminalCount; i++) {
			lhs1prod[i] = new HashSet<Production>();
			lhs2prod[i] = new HashSet<Production>();
			rhs2prod[i] = new HashSet<Production>();
		}

        Vector<Production> dynamicLen1Prods = new Vector<>();
        Vector<Production> dynamicLen2Prods = new Vector<>();
        for (Production prod : productions) {
            if (prod.right.length == 2) {
                dynamicLen2Prods.add(prod);
                lhs2prod[prod.left - terminalCount].add(prod);
                rhs2prod[prod.right[0] - terminalCount].add(prod);
            } else {
                dynamicLen1Prods.add(prod);
                lhs1prod[prod.left - terminalCount].add(prod);
            }
        }

        length1productionCount = dynamicLen1Prods.size();
        length2productionCount = dynamicLen2Prods.size();
        length1productions = new Production[length1productionCount];
        dynamicLen1Prods.toArray(length1productions);
        length2productions = new Production[length2productionCount];
        dynamicLen2Prods.toArray(length2productions);

        // Removes nonTerminals from Encoder
        for (String nonTerminal : nonTerminals) {
            tokenEncoder.remove(nonTerminal);
        }
    }

    public CFG(InputReader reader) {
	    terminalCount = reader.getInt();
	    nonTerminalCount = reader.getInt();
	    length1productionCount = reader.getInt();
		length2productionCount = reader.getInt();
	    length1productions = new Production[length1productionCount];
		length2productions = new Production[length2productionCount];
		lhs1prod = new Set[nonTerminalCount];
		lhs2prod = new Set[nonTerminalCount];
		rhs2prod = new Set[nonTerminalCount];
		for (int i = 0; i < nonTerminalCount; i++) {
			lhs1prod[i] = new HashSet<Production>();
			lhs2prod[i] = new HashSet<Production>();
			rhs2prod[i] = new HashSet<Production>();
		}
		for(int i=0; i<length1productionCount; i++) {
			length1productions[i] = new Production(1);
			length1productions[i].left = reader.getInt();
			length1productions[i].right = new int[1];
			length1productions[i].right[0] = reader.getInt();
			lhs1prod[length1productions[i].left - terminalCount].add(length1productions[i]);
		}
		for(int i=0; i<length2productionCount; i++) {
			length2productions[i] = new Production(2);
			length2productions[i].left = reader.getInt();
			length2productions[i].right = new int[2];
			length2productions[i].right[0] = reader.getInt();
			length2productions[i].right[1] = reader.getInt();
			lhs2prod[length2productions[i].left - terminalCount].add(length2productions[i]);
			rhs2prod[length2productions[i].right[0] - terminalCount].add(length2productions[i]);
		}
    }

    public void printTokens() {
        for (int i = 0; i < terminalCount; i++) {
            System.out.println(i + ":" + tokenDecoder.get(i));
        }
    }

    public int terminalCount() {
        return terminalCount;
    }
    public int nonTerminalCount() {
        return nonTerminalCount;
    }
    
    public int productionCount() {
        return productionCount;
    }

    public int length1productionCount() {
	    return length1productionCount;
    }

    public Production[] length1productions() { return length1productions; }

	public int length2productionCount() {
		return length2productionCount;
	}
	public Production[] length2productions() { return length2productions; }

	public Set<Production>[] lhs1prod() { return lhs1prod; }
	public Set<Production>[] lhs2prod() { return lhs2prod; }
	public Set<Production>[] rhs2prod() { return rhs2prod; }

}


