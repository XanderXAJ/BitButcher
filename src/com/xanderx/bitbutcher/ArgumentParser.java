package com.xanderx.bitbutcher;

import java.util.HashSet;

public class ArgumentParser {
	
	public static HashSet<String> parseArguments(String args) {
		HashSet<String> set = new HashSet<String>();
		
		for (Character character : args.toCharArray()) {
			set.add(character.toString());
		}
		
		return set;
	}
	
}
