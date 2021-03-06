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
	/** Amount to check after the Application End Offset (in case it is wrong) */
	private static final int AEO_BUFFER = 1024 * 4; // 4KB
	/** Amount of data to initially check when scanning from end of file */
	private static final int PARANOIA_BUFFER = 1024 * 4; // 4KB
	
	private static final byte ALL_ZEROES = 0; // I know it's obvious, but the code becomes more clear
	private static final byte ALL_ONES = -1; // Two's complement, mmm
	
	private static final int APPLICATION_END_OFFSET_OFFSET = 8 * 16; // I couldn't think of a better name
	
	private static final int WIFI_ENABLED_GAME_ADDITIONAL_OFFSET = 136;
	
	/** Constants for handling files */
	private static final String INPUT_MODE = "rw"; // Read and write access to file
	private static final String COMPRESSED_SUFFIX = " trim";
	private static final String FILE_NAME_DELIMITER = ".";
	private static final String DS_ROM_EXTENSION = ".nds";
	
	
	private static long pNumberOfChunks;
	private static RandomAccessFile pStream;
	private static boolean pParanoid = false;
	private static boolean pReadAeo = true;
	private static boolean pExtensionMatters = true;
	
	
	public static void main(final String[] args) {
		if (args.length == 0) {
			System.out.println("Usage: java BitButcher [-eip] rom1 rom2 romN...");
		} else {
			final HashSet<File> files = new HashSet<File>();
			
			// Check to see if any of the arguments are switches/options
			// Otherwise assume they are files -- check to see if they exist
			for (String arg : args) {
				if (arg.startsWith("-")) {
					HashSet<String> argSet = ArgumentParser.parseArguments(arg);
					
					if (argSet.contains("p")) {
						System.out.println("Running in Paranoia Mode..!");
						pParanoid = true;
						
						if (argSet.contains("i")) {
							System.out.println("Also ignoring the AEO..!");
							pReadAeo = false;
						}
					}
					if (argSet.contains("e")) {
						System.out.println("Ignoring file extensions...");
						pExtensionMatters = false;
					}
				} else {
					File file = new File(arg);
					
					/*	Check file name is valid
						If extensions does not matter, file name is valid
						Otherwise if extensions do matter:
							If file name is too short, then file name is invalid
							If file name is of adequate length, then:
								if extension matches, file name is valid
								if extension does not match, file name is invalid
					*/
					
					boolean validFileName = !pExtensionMatters;
					
					if (pExtensionMatters) {
						if (file.getName().length() >= DS_ROM_EXTENSION.length()) {
							String fileNameEnd = file.getName().substring(file.getName().length() - DS_ROM_EXTENSION.length());
							validFileName = fileNameEnd.equalsIgnoreCase(DS_ROM_EXTENSION);
						}
					}
					
					if (validFileName && file.exists()) {
						files.add(file);
					}
				}
			}
		
			// Trim each file
			if (files.size() != 0) {
				long totalDifference = 0l;
				for (File file : files) {
					totalDifference = totalDifference + trimRom(file);
				}
		
				// Output total difference made
				System.out.println("Total difference: " + totalDifference);
			} else {
				System.out.println("... I need something to trim, you know!");
			}
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
				
				long lastSaneByte = 0;
				
				// 3 different states:
				//  1) Not paranoid: Read AEO, resize accordingly.
				//  2) Paranoid: Read AEO, resize by scanning from end if file size and AEO do NOT match. Size cannot be smaller than AEO.
				//  3) Paranoid, ignore: Do not read AEO, resize by scanning from end.
				
				if (pReadAeo) {
					/*	Java integers are big-endian.  However, the AEO is little-endian.
						The Java NIO framework can convert endianness.
						Therefore, read in the bytes needed, then use NIO to convert
						the byte array into an int of the correct endianness.
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
					byte[] buffer = new byte[AEO_BUFFER];
					pStream.seek(lastSaneByte);
					pStream.read(buffer);
					int offset = findLastSaneByte(buffer);
					lastSaneByte += offset;
					
					if (offset == 0) {
						System.out.println("AEO is correct.");
					} else {
						System.out.println("AEO is incorrect by " + offset + ", corrected to: " + lastSaneByte);
						if (offset == WIFI_ENABLED_GAME_ADDITIONAL_OFFSET){
							System.out.println("This is probably a wi-fi enabled game.");
						}
					}
				}
				
				if (pParanoid) {
					System.out.println("Scanning the file for dummy data...");
					
					long fileSize = pStream.length();
					int bufferSize = PARANOIA_BUFFER;
					long smallestAllowedSize = lastSaneByte;
					
					do {
						fileSize -= bufferSize;
						
						byte[] bytes = new byte[bufferSize];
						pStream.seek(fileSize);
						pStream.readFully(bytes);
						
						lastSaneByte = findLastSaneByte(bytes);
						
						System.out.println("Buffer size: " + bufferSize);
						System.out.println("Last sane byte in buffer: " + lastSaneByte);
						
						bufferSize *= 2; // A logarithmic buffer seemed awesome at the time
					} while (lastSaneByte == 0);
					
					lastSaneByte += fileSize;
					
					System.out.println("Found last sane byte: " + lastSaneByte);
					
					if (lastSaneByte < smallestAllowedSize) {
						System.out.println("The AEO suggests that the ROM is larger than what the paranoia check found.");
						System.out.println("The last sane byte has been corrected to match the AEO: " + smallestAllowedSize);
						lastSaneByte = smallestAllowedSize;
					}
				}
				
				if (lastSaneByte > 0) {
					difference = lastSaneByte - pStream.length();
				
					// Resize ROM
					System.out.println("Resizing " + rom.getName() + ":");
					System.out.println("Previously: " + pStream.length() + ", Now: " + lastSaneByte + ", Difference: " + difference);
					pStream.setLength(lastSaneByte);
				} else {
					// Something has gone horribly wrong, do NOT attempt to trim
					System.out.println("Something has gone wrong -- the last sane byte came back as zero.");
					System.out.println("I will not touch the file as a result.");
					difference = 0;
				}
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
	
	/** Checks the passed byte to see if it is dummy data.
		
		@arg data The byte to check
		@return Whether the data is likely to be dummy or not
	*/
	private static boolean isDummyData(final byte data) {
		return data == ALL_ONES || data == ALL_ZEROES;
	}
	
	/**	Checks to see if the passed array is made up entirely of dummy data.
		1 byte of ones = -1  --  bytes are signed!
		
		@arg bytes The bytes to check through
	 	@return Whether the byte array is completely made up of dummy data
	*/
	private static boolean isAllDummyData(final byte[] bytes) {
		boolean allDummyData = true;
		
		for (int i = 0; allDummyData && i < bytes.length; i++) {
			allDummyData = isDummyData(bytes[i]);
		}
		
		return allDummyData;
	}
	
	/**	Checks each byte in an attempt to find the exact byte where dummy data begins.
		
		@arg bytes The bytes to check through
		@return The offset of the last byte that is not dummy data
	*/
	private static int findLastSaneByte(final byte[] bytes) {
		int lastSaneByte = 0;
		
		for (int i = bytes.length - 1; lastSaneByte == 0 && i >= 0; i--) {
			if (!isDummyData(bytes[i])) {
				// Add one, as the first byte of a chunk isn't the zeroth byte in the file, and so on
				// Basically, file size is one-based, not zero-based
				lastSaneByte = i + 1;
			}
		}
		
		return lastSaneByte;
	}
	
	/** Returns whether ot not the passed number is a power of two.
		
		@arg number The number to check
		@return Whether or not the number os a power of two
	*/
	private static boolean isPowerOfTwo(final long number) {
		long powerOfTwo = 1;
		
		while (number != powerOfTwo && powerOfTwo < number) {
			//System.out.println(number + " == " + powerOfTwo + "?");
			powerOfTwo *= 2;
		}
		
		return number == powerOfTwo;
	}
	
	/**	Chooses a new unique name similar to the passed file.
		It's for testing really.
		@arg oldFile A file reperesenting the old name to augment
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
