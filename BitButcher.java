import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashSet;

/**
	Small ROM trimmer for removing useless data from Nintendo DS ROMs.
	
	Untrimmed ROMs contain a continuous sequence of 1s (ones) in their binary form.
	
	This data only exists because the original catridges come in powers of two,
	and serves no purpose in a ROM.  This data can be safely removed whilst keeping
	the ROM playable.
*/
public class BitButcher {
	/** Minimum length of ones required to be deemed padding */
	private static int BUFFER = 1024 * 32; // 32KB
	
	private static byte ALL_ONES = -1;
	
	private static String COMPRESSED_SUFFIX = " trim";
	private static String INPUT_MODE = "rw";
	private static String FILE_NAME_DELIMITER = ".";
	
	private static String PROGRESS_INDICATOR = ".";
	private static int PROGRESS_INDICATOR_INTERVAL = 1024 * 1024; // 1MB
	private static int PROGRESS_INDICATOR_BREAK_INTERVAL = PROGRESS_INDICATOR_INTERVAL * 32; // 32MB
	
	private static long pNumberOfChunks;
	private static RandomAccessFile pStream;
	
	
	public static void main(String[] args) {
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
	
	/**	Trims the passed ROM to its minimum possible size.
		@return The difference between the original size and the new size
	*/
	private static long trimRom(File rom) {
		long difference = 0l;
		try {
			pStream = new RandomAccessFile(rom, INPUT_MODE);
				try {
					// Seek around, seek around, seek around now
					System.out.println("Reading " + rom.getName() + ":");
					
					/*	Do a sort-of binary search:
						Imagine the file is made of chunks of size BUFFER.  Work out the number of chunks in a given file.
						The entire file is the first active area.
						1) Take the middle chunk of the active area and test it for ones.
						2) If the are BUFFER bytes of ones, make the first half the new focus.
						3) If there are not BUFFER bytes of ones, make the second half the new focus.
						4) Go back to step 1 and repeat until the first block all made up of ones is found.
						5) Scan the previous block one byte at a time and find exactly where the dummy data begins.
						6) Truncate the file to this point.
					*/
					
					// Find number of chunks in file
					pNumberOfChunks = (long) Math.ceil((double)pStream.length() / (double)BUFFER);
					System.out.println("Total number of chunks: " + pNumberOfChunks);
					
					final long lastSaneByte = findLastSaneByte(0l, pNumberOfChunks);
					difference = lastSaneByte - pStream.length();
					
					// Resize ROM
					System.out.println("Resizing " + rom.getName() + ":");
					System.out.println("Previously: " + pStream.length() + ", Now: " + lastSaneByte + ", Difference: " + difference);
					pStream.setLength(lastSaneByte);
					
					// Close file handle
					pStream.close();
					
					System.out.println("Done");
					System.out.println();
					
					return difference;
				}
				catch (IOException e) {
					System.err.println("BOOM.  Something's gone wrong.  Check you have write permissions for this file:");
					System.err.println(rom.getPath());
				}
			/*}
			catch (IOException e) {
				System.err.println("I can't write.  Ah.  Check you have write permissions in this folder.");
				System.err.println(rom.getParent());
			}*/
		}
		catch (IOException e) {
			System.err.println("I can't read the file.  That sucks.");
		}
		return difference;
	}
	
	private static long findLastSaneByte(long beginningChunk, long endChunk) throws IOException {
		long lastSaneByte;
		
		if (beginningChunk == endChunk) {
			// Holy snap, we've found the first chunk of ones.
			//System.out.println("Found first chunk " + beginningChunk + " full of padding, deeply scanning " + (beginningChunk - 1) + "...");
			
			// If I'm going to trim to the byte, I'd minus one off of beginningChunk and scan from there
			lastSaneByte = (beginningChunk - 1) * BUFFER;
			
			byte[] bytes = getCorrectSizeByteArray(beginningChunk - 1);
			pStream.seek(lastSaneByte);
			pStream.read(bytes);
			final int finalByteOffset = deeplyScanForLastSaneByte(bytes);
			bytes = null;
			
			lastSaneByte = lastSaneByte + finalByteOffset;
		} else {
			// Work out what the middle chunk is
			// The loss of precision is deliberate; it's a free floor (rounding down)
			long middleChunk = beginningChunk + ((endChunk - beginningChunk) / 2);
			
			// Read in the middle chunk and
			// check it to see if it is all ones or not
			//System.out.println("Reading chunk " + middleChunk + " (low: " + beginningChunk + ", high: " + endChunk + ")");
			
			byte[] bytes = getCorrectSizeByteArray(middleChunk);
			pStream.seek(middleChunk * BUFFER);
			pStream.read(bytes);
			final boolean allOnes = isAllOnes(bytes);
			bytes = null;
			
			if (allOnes) { // All ones, choose first half as active area
				lastSaneByte = findLastSaneByte(beginningChunk, middleChunk);
			} else { // Not all ones, choose the second half (minus the middle due to rounding down) as the active area
				lastSaneByte = findLastSaneByte(middleChunk + 1, endChunk);
			}
		}
		
		return lastSaneByte;
	}
	
	/**	Checks to see if the passed array is made up entirely of ones.
		1 byte of ones = -1  --  bytes are signed!
		
	 	@return Whether the byte array is all made up of ones
	*/
	private static boolean isAllOnes(byte[] bytes) {
		boolean allOnes = true;
		
		for (int i = 0; allOnes && i < bytes.length; i++) {
			allOnes = bytes[i] == ALL_ONES;
		}
		
		return allOnes;
	}
	
	/**	
		
	*/
	private static int deeplyScanForLastSaneByte(byte[] bytes) {
		int lastSaneByte = 0;
		
		for (int i = 0; i < bytes.length; i++) {
			//System.out.print(bytes[i] + ",");
			if (bytes[i] != ALL_ONES) {
				// Add one, as the first byte of a chunk isn't the zeroth byte in the file, etc.
				lastSaneByte = i + 1;
			}
		}
		
		return lastSaneByte;
	}
	
	private static boolean isLastChunk(long chunk) {
		return chunk == pNumberOfChunks - 1;
	}
	
	private static byte[] getCorrectSizeByteArray(long chunk) throws IOException {
		// What happens if the last chunk is the size of the BUFFER?  0.  Snap.
		//System.out.println("File size: " + pStream.length());
		//System.out.println("Final chunk size: " + (int)(pStream.length() % BUFFER));
		if (isLastChunk(chunk) && (pStream.length() % BUFFER) != 0) {
			return new byte[(int)(pStream.length() % BUFFER)];
		} else {
			return new byte[BUFFER];
		}
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
