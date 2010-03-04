package com.xanderx.bitbutcher;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;

/**
	BitButcher
	Small ROM trimmer for removing useless data from Nintendo DS ROMs.
	
	Legal:
	Copyright 2010 Alex Palmer.

	This program is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.

	This program is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with this program.  If not, see <http://www.gnu.org/licenses/>.

	See the COPYING file for the full license.
	
	
	Explanation:
	Untrimmed ROMs contain a continuous sequence of 1s (ones) in their binary form.
	
	This data only exists because the original catridges come in powers of two,
	and serves no purpose in a ROM.  This data can be safely removed whilst keeping
	the ROM playable.
*/
public class BitButcher {
	/** Minimum length of ones required to be deemed padding */
	private static final int BUFFER = 1024 * 32; // 32KB
	/** Amount to check after the Application End Offset (in case it is wrong) */
	private static final int PARANOIA_BUFFER = 1024 * 4; // 4KB
	
	private static final byte ALL_ZEROES = 0; // I know it's obvious, but the code becomes more clear
	private static final byte ALL_ONES = -1; // Two's complement, mmm
	
	private static final int APPLICATION_END_OFFSET_OFFSET = 8 * 16; // I couldn't think of a better name
	
	/** Constants for naming files */
	private static final String INPUT_MODE = "rw"; // Read and write access to file
	private static final String COMPRESSED_SUFFIX = " trim";
	private static final String FILE_NAME_DELIMITER = ".";
	
	/** Constants for progress indication */
	private static final String PROGRESS_INDICATOR = ".";
	private static final int PROGRESS_INDICATOR_INTERVAL = 1024 * 1024; // 1MB
	private static final int PROGRESS_INDICATOR_BREAK_INTERVAL = PROGRESS_INDICATOR_INTERVAL * 32; // 32MB
	
	
	private static long pNumberOfChunks;
	private static RandomAccessFile pStream;
	private static boolean pParanoid = false;
	
	
	public static void main(final String[] args) {
		if (args.length == 0) {
			System.out.println("Usage: java BitButcher rom1 rom2 romN...");
		} else {
			final HashSet<File> files = new HashSet<File>();
		
			// Create a list of unique, existing files from arguments
			// Read in arguments and create File objects
			for (String arg : args) {
				File file = new File(arg);
			
				if (file.exists()) {
					files.add(file);
				}
			}
		
			// Trim each file
			long totalDifference = 0l;
			for (File file : files) {
				totalDifference = totalDifference + trimRom(file);
			}
		
			// Output total difference made
			System.out.println("Total difference: " + totalDifference);
		}
	}
	
	/**	Trims the passed ROM to its minimum possible size.
		@return The difference between the original size and the new size
	*/
	private static long trimRom(final File rom) {
		long difference = 0l;
		try {
			pStream = new RandomAccessFile(rom, INPUT_MODE);
			try {
				// Seek around, seek around, seek around now
				System.out.println("Reading " + rom.getName() + ":");
				
				long lastSaneByte = pStream.length();
				
				if (pParanoid || !isPowerOfTwo(lastSaneByte)) { // Paranoia mode, read file from the end backwards
					// Find number of chunks in file
					pNumberOfChunks = (long) Math.ceil((double)lastSaneByte / (double)BUFFER);
					//System.out.println("Total number of chunks: " + pNumberOfChunks);
					
					// Find the last sane byte!
					lastSaneByte = findLastSaneByte(0l, pNumberOfChunks);
					System.out.println("Found last sane byte: " + lastSaneByte);
				} else { // Normal mode, read Application End Offset and trim according to that
					/*	Java integers are big-endian.  However, the AEO is little-endian.
						The Java NIO framework can convert endianness.
						Therefore, read in the bytes needed, then use NIO to convert the byte array
						into an int of the correct endianness.
					*/
					// Read AEO (which is little-endian)
					pStream.seek(APPLICATION_END_OFFSET_OFFSET);
					byte[] aeo = new byte[4];
					pStream.readFully(aeo);
					
					// Tell Java the byte order is little-endian, and retrieve the resulting int
					ByteBuffer bb = ByteBuffer.wrap(aeo);
					bb.order(ByteOrder.LITTLE_ENDIAN);
					lastSaneByte = bb.getInt();
					bb = null;
					aeo = null;
					
					System.out.println("Read the Application End Offset: " + lastSaneByte);
					
					// Check some of the file after the AEO to be sure it was correct
					byte[] buffer = new byte[PARANOIA_BUFFER];
					pStream.seek(lastSaneByte);
					pStream.read(buffer);
					int offset = deeplyScanForLastSaneByte(buffer);
					lastSaneByte += offset;
					
					if (offset == 0) {
						System.out.println("AEO is correct.");
					} else {
						System.out.println("AEO is incorrect by " + offset + ", corrected to: " + lastSaneByte);
					}
				}
				
				difference = lastSaneByte - pStream.length();
				
				// Resize ROM
				/*System.out.println("Resizing " + rom.getName() + ":");
				System.out.println("Previously: " + pStream.length() + ", Now: " + lastSaneByte + ", Difference: " + difference);
				pStream.setLength(lastSaneByte);*/
				
				// Close file handle
				pStream.close();
				
				System.out.println("Done");
				System.out.println();
			}
			catch (IOException e) {
				System.err.println("BOOM.  Something's gone wrong.  Check you have write permissions for this file:");
				System.err.println(rom.getPath());
			}
		}
		catch (IOException e) {
			System.err.println("I can't read the file at all.  That sucks.\nCheck you have read and write permissions for this file, and that it still exists:");
			System.err.println(rom.getPath());
		}
		return difference;
	}
	
	private static long findLastSaneByte(final long beginningChunk, final long endChunk) throws IOException {
		long lastSaneByte;
		
		if (beginningChunk == endChunk) {
			// Holy snap, we've found the first chunk of ones.
			//System.out.println("Found first chunk " + beginningChunk + " full of padding, deeply scanning " + (beginningChunk - 1) + "...");
			
			// If I'm going to trim to the byte, I'd minus one off of beginningChunk and scan from there (since the chunk found is all padding)
			lastSaneByte = (beginningChunk - 1) * BUFFER;
			
			byte[] bytes = new byte[(int)(pStream.length() - lastSaneByte)];
			pStream.seek(lastSaneByte);
			pStream.read(bytes);
			final int finalByteOffset = deeplyScanForLastSaneByte(bytes);
			bytes = null;
			
			lastSaneByte = lastSaneByte + finalByteOffset;
		} else {
			// Work out what the middle chunk is
			// The loss of precision is deliberate; it's a free floor (a.k.a. rounding down)
			long middleChunk = beginningChunk + ((endChunk - beginningChunk) / 2);
			
			// Read in the middle chunk and
			// check it to see if it is all dummy data or not
			//System.out.println("Reading chunk " + middleChunk + " (low: " + beginningChunk + ", high: " + endChunk + ")");
			
			byte[] bytes = getCorrectSizeByteArray(middleChunk);
			pStream.seek(middleChunk * BUFFER);
			pStream.read(bytes);
			final boolean allDummyData = isAllDummyData(bytes);
			bytes = null;
			
			if (allDummyData) { // All ones, choose first half as active area
				lastSaneByte = findLastSaneByte(beginningChunk, middleChunk);
			} else { // Not all ones, choose the second half (minus the middle due to rounding down) as the active area
				lastSaneByte = findLastSaneByte(middleChunk + 1, endChunk);
			}
		}
		
		return lastSaneByte;
	}
	
	private static boolean isDummyData(final byte data) {
		return data == ALL_ONES || data == ALL_ZEROES;
	}
	
	/**	Checks to see if the passed array is made up entirely of ones.
		1 byte of ones = -1  --  bytes are signed!
		
	 	@return Whether the byte array is all made up of ones
	*/
	private static boolean isAllDummyData(final byte[] bytes) {
		boolean allDummyData = true;
		
		for (int i = 0; allDummyData && i < bytes.length; i++) {
			allDummyData = isDummyData(bytes[i]);
		}
		
		return allDummyData;
	}
	
	/**	
		
	*/
	private static int deeplyScanForLastSaneByte(final byte[] bytes) {
		int lastSaneByte = 0;
		
		for (int i = 0; i < bytes.length; i++) {
			if (!isDummyData(bytes[i])) {
				// Add one, as the first byte of a chunk isn't the zeroth byte in the file, and so on
				lastSaneByte = i + 1;
			}
		}
		
		return lastSaneByte;
	}
	
	private static boolean isLastChunk(final long chunk) {
		return chunk == pNumberOfChunks - 1;
	}
	
	private static byte[] getCorrectSizeByteArray(final long chunk) throws IOException {
		// What happens if the last chunk is the size of the BUFFER?  0.  Snap.
		//System.out.println("File size: " + pStream.length());
		//System.out.println("Final chunk size: " + (int)(pStream.length() % BUFFER));
		if (isLastChunk(chunk) && (pStream.length() % BUFFER) != 0) {
			return new byte[(int)(pStream.length() % BUFFER)];
		} else {
			return new byte[BUFFER];
		}
	}
	
	private static boolean isPowerOfTwo(final long number) {
		return true; //TODO
	}
	
	/**	Chooses a new unique name similar to the passed file.
		It's for testing really.
		@return A File representing a file that does not already exist
	*/
	private static File chooseNewFileName(File oldFile) throws IOException {
		String oldPath = oldFile.getCanonicalPath();
		
		// Find where to insert characters
		int index = oldPath.lastIndexOf(FILE_NAME_DELIMITER);
		
		if (index == -1) {
			index = oldPath.length();
		}
		
		// Insert characters and test until unique name is found
		int count = 0;
		boolean unique = false;
		File newFile;
		
		do {
			String newPath = new String(oldPath.substring(0,index) + COMPRESSED_SUFFIX + count + oldPath.substring(index));
			newFile = new File(newPath);
			count++;
		} while (newFile.exists());
		
		return newFile;
	}
}
