package su.plo.matter;

import me.earthme.luminol.config.modules.function.SecureSeedConfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class HashingV2 {
    private static final int BLOCK_LEN = 64;
    private static final int CHUNK_LEN = 1024;
    private static final int OUT_LEN = 32;
    private static final int KEY_LEN = 32;

    private static final int CHUNK_START = 1 << 0;
    private static final int CHUNK_END = 1 << 1;
    private static final int PARENT = 1 << 2;
    private static final int ROOT = 1 << 3;
    private static final int KEYED_HASH = 1 << 4;
    private static final int DERIVE_KEY_CONTEXT = 1 << 5;
    private static final int DERIVE_KEY_MATERIAL = 1 << 6;

    private static final int[] IV = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
            0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
    };

    private static final int[] MSG_PERMUTATION = {2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8};

    private static final ThreadLocal<SaltHolder> saltHolderLocal = ThreadLocal.withInitial(() -> {
        final SaltHolder newHolder = new SaltHolder();

        newHolder.init(SecureSeedConfig.salt);

        return newHolder;
    });
    private static final ThreadLocal<byte[]> threadBuffer = ThreadLocal.withInitial(() -> new byte[128]);
    private static final ThreadLocal<ByteBuffer> threadByteBuffer = ThreadLocal.withInitial(() -> ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN));

    private static int rotr32(int x, int k) {
        return ((x >>> k) | (x << (32 - k))) >>> 0;
    }

    private static void g(int[] state, int a, int b, int c, int d, int mx, int my) {
        state[a] = (state[a] + state[b] + mx) >>> 0;
        state[d] = rotr32(state[d] ^ state[a], 16);
        state[c] = (state[c] + state[d]) >>> 0;
        state[b] = rotr32(state[b] ^ state[c], 12);
        state[a] = (state[a] + state[b] + my) >>> 0;
        state[d] = rotr32(state[d] ^ state[a], 8);
        state[c] = (state[c] + state[d]) >>> 0;
        state[b] = rotr32(state[b] ^ state[c], 7);
    }

    private static void round(int[] state, int[] m) {
        g(state, 0, 4, 8, 12, m[0], m[1]);
        g(state, 1, 5, 9, 13, m[2], m[3]);
        g(state, 2, 6, 10, 14, m[4], m[5]);
        g(state, 3, 7, 11, 15, m[6], m[7]);
        g(state, 0, 5, 10, 15, m[8], m[9]);
        g(state, 1, 6, 11, 12, m[10], m[11]);
        g(state, 2, 7, 8, 13, m[12], m[13]);
        g(state, 3, 4, 9, 14, m[14], m[15]);
    }

    private static int[] permute(int[] m) {
        int[] permuted = new int[16];
        for (int i = 0; i < 16; i++) {
            permuted[i] = m[MSG_PERMUTATION[i]];
        }
        return permuted;
    }

    private static int[] compress(int[] chainingValue, int[] blockWords, long counter, int blockLen, int flags) {
        int counterLow = (int) (counter >>> 0);
        int counterHigh = (int) ((counter / 0x100000000L) >>> 0);

        int[] state = new int[16];
        System.arraycopy(chainingValue, 0, state, 0, 8);
        state[8] = IV[0];
        state[9] = IV[1];
        state[10] = IV[2];
        state[11] = IV[3];
        state[12] = counterLow;
        state[13] = counterHigh;
        state[14] = blockLen;
        state[15] = flags;

        int[] block = Arrays.copyOf(blockWords, 16);

        for (int i = 0; i < 7; i++) {
            round(state, block);
            block = permute(block);
        }

        int[] output = new int[16];
        for (int i = 0; i < 8; i++) {
            output[i] = state[i] ^ state[i + 8];
            output[i + 8] = state[i + 8] ^ chainingValue[i];
        }

        return output;
    }

    private static int[] first8Words(int[] compressionOutput) {
        return Arrays.copyOfRange(compressionOutput, 0, 8);
    }

    private static int[] wordsFromLittleEndianBytes(byte[] bytes) {
        int[] words = new int[bytes.length / 4];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < words.length; i++) {
            words[i] = buffer.getInt(i * 4);
        }
        return words;
    }

    private static byte[] wordsToLittleEndianBytes(int[] words, int outputLen) {
        byte[] output = new byte[outputLen];
        ByteBuffer buffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < words.length && i * 4 < outputLen; i++) {
            buffer.putInt(i * 4, words[i]);
        }
        return output;
    }

    private static class ChunkState {
        int[] chainingValue;
        long chunkCounter;
        byte[] block;
        int blockLen;
        int blocksCompressed;
        int flags;

        ChunkState(int[] keyWords, long chunkCounter, int flags) {
            this.chainingValue = keyWords.clone();
            this.chunkCounter = chunkCounter;
            this.block = new byte[BLOCK_LEN];
            this.blockLen = 0;
            this.blocksCompressed = 0;
            this.flags = flags;
        }

        int len() {
            return BLOCK_LEN * this.blocksCompressed + this.blockLen;
        }

        int startFlag() {
            return this.blocksCompressed == 0 ? CHUNK_START : 0;
        }

        void update(byte[] input) {
            int offset = 0;

            while (offset < input.length) {
                if (this.blockLen == BLOCK_LEN) {
                    int[] blockWords = wordsFromLittleEndianBytes(this.block);
                    this.chainingValue = first8Words(compress(
                            this.chainingValue,
                            blockWords,
                            this.chunkCounter,
                            BLOCK_LEN,
                            this.flags | this.startFlag()
                    ));
                    this.blocksCompressed++;
                    this.block = new byte[BLOCK_LEN];
                    this.blockLen = 0;
                }

                int want = BLOCK_LEN - this.blockLen;
                int take = Math.min(want, input.length - offset);
                System.arraycopy(input, offset, this.block, this.blockLen, take);
                this.blockLen += take;
                offset += take;
            }
        }

        int[] outputBlockWords() {
            int[] blockWords = new int[16];
            for (int i = 0; i < 16 && i * 4 < this.blockLen; i++) {
                blockWords[i] = ((this.block[i * 4 + 0] & 0xFF)) |
                        ((this.block[i * 4 + 1] & 0xFF) << 8) |
                        ((this.block[i * 4 + 2] & 0xFF) << 16) |
                        ((this.block[i * 4 + 3] & 0xFF) << 24);
            }
            return blockWords;
        }

        Output output() {
            return new Output(
                    this.chainingValue,
                    outputBlockWords(),
                    this.chunkCounter,
                    this.blockLen,
                    this.flags | this.startFlag() | CHUNK_END
            );
        }
    }

    private static class Output {
        int[] inputChainingValue;
        int[] blockWords;
        long counter;
        int blockLen;
        int flags;

        Output(int[] inputChainingValue, int[] blockWords, long counter, int blockLen, int flags) {
            this.inputChainingValue = inputChainingValue;
            this.blockWords = blockWords;
            this.counter = counter;
            this.blockLen = blockLen;
            this.flags = flags;
        }

        int[] chainingValue() {
            return first8Words(compress(
                    this.inputChainingValue,
                    this.blockWords,
                    this.counter,
                    this.blockLen,
                    this.flags
            ));
        }

        byte[] rootOutputBytes(int outLen) {
            byte[] output = new byte[outLen];
            long outputBlockCounter = 0;

            for (int offset = 0; offset < outLen; offset += 2 * OUT_LEN) {
                int[] words = compress(
                        this.inputChainingValue,
                        this.blockWords,
                        outputBlockCounter,
                        this.blockLen,
                        this.flags | ROOT
                );

                byte[] blockOutput = wordsToLittleEndianBytes(words, 64);

                int remaining = outLen - offset;
                int copyLen = Math.min(remaining, 64);
                System.arraycopy(blockOutput, 0, output, offset, copyLen);

                outputBlockCounter++;
            }

            return output;
        }
    }

    private static Output parentOutput(int[] leftChildCv, int[] rightChildCv, int[] keyWords, int flags) {
        int[] blockWords = new int[16];
        System.arraycopy(leftChildCv, 0, blockWords, 0, 8);
        System.arraycopy(rightChildCv, 0, blockWords, 8, 8);

        return new Output(
                keyWords,
                blockWords,
                0,
                BLOCK_LEN,
                PARENT | flags
        );
    }

    private static int[] parentCv(int[] leftChildCv, int[] rightChildCv, int[] keyWords, int flags) {
        return parentOutput(leftChildCv, rightChildCv, keyWords, flags).chainingValue();
    }

    private static class SaltHolder {
        private long[] cachedSaltHash = null;
        private String lastSalt = null;

        public void init(String currentSalt) {
            if (cachedSaltHash == null || !currentSalt.equals(lastSalt)) {
                byte[] saltBytes = currentSalt.getBytes();
                byte[] hashBytes = blake3(saltBytes);
                int[] hashInts = bytesToIntsLittleEndian(hashBytes);

                cachedSaltHash = new long[8];
                for (int i = 0; i < 8; i++) {
                    cachedSaltHash[i] = (hashInts[i] & 0xFFFFFFFFL);
                }
                lastSalt = currentSalt;
            }
        }
    }

    private static class Hasher {
        ChunkState chunkState;
        int[] keyWords;
        java.util.ArrayList<int[]> cvStack;
        int flags;

        Hasher(int[] keyWords, int flags) {
            this.chunkState = new ChunkState(keyWords, 0, flags);
            this.keyWords = keyWords.clone();
            this.cvStack = new java.util.ArrayList<>();
            this.flags = flags;
        }

        static Hasher createNew() {
            int[] iv = {IV[0], IV[1], IV[2], IV[3], IV[4], IV[5], IV[6], IV[7]};
            return new Hasher(iv, 0);
        }

        static Hasher createKeyed(byte[] key) {
            if (key.length != KEY_LEN) {
                throw new IllegalArgumentException("Key must be " + KEY_LEN + " bytes");
            }
            int[] keyWords = wordsFromLittleEndianBytes(key);
            return new Hasher(keyWords, KEYED_HASH);
        }

        void pushStack(int[] cv) {
            cvStack.add(cv);
        }

        int[] popStack() {
            return cvStack.remove(cvStack.size() - 1);
        }

        void addChunkChainingValue(int[] newCv, long totalChunks) {
            int[] cv = newCv;
            long chunks = totalChunks;

            while ((chunks & 1) == 0) {
                cv = parentCv(popStack(), cv, this.keyWords, this.flags);
                chunks >>>= 1;
            }

            pushStack(cv);
        }

        void update(byte[] input) {
            int offset = 0;

            while (offset < input.length) {
                if (this.chunkState.len() == CHUNK_LEN) {
                    int[] chunkCv = this.chunkState.output().chainingValue();
                    long totalChunks = this.chunkState.chunkCounter + 1;
                    addChunkChainingValue(chunkCv, totalChunks);
                    this.chunkState = new ChunkState(this.keyWords, totalChunks, this.flags);
                }

                int want = CHUNK_LEN - this.chunkState.len();
                int take = Math.min(want, input.length - offset);
                byte[] chunk = new byte[take];
                System.arraycopy(input, offset, chunk, 0, take);
                this.chunkState.update(chunk);
                offset += take;
            }
        }

        byte[] finalize(int outLen) {
            Output output = this.chunkState.output();
            int parentNodesRemaining = this.cvStack.size();

            while (parentNodesRemaining > 0) {
                parentNodesRemaining--;
                output = parentOutput(
                        this.cvStack.get(parentNodesRemaining),
                        output.chainingValue(),
                        this.keyWords,
                        this.flags
                );
            }

            return output.rootOutputBytes(outLen);
        }
    }

    private static byte[] blake3(byte[] data) {
        Hasher hasher = Hasher.createNew();
        hasher.update(data);
        return hasher.finalize(OUT_LEN);
    }

    private static byte[] blake3Keyed(byte[] data, byte[] key) {
        Hasher hasher = Hasher.createKeyed(key);
        hasher.update(data);
        return hasher.finalize(OUT_LEN);
    }

    private static byte[] blake3Keyed(byte[] data, byte[] key, int outLen) {
        Hasher hasher = Hasher.createKeyed(key);
        hasher.update(data);
        return hasher.finalize(outLen);
    }

    private static int[] bytesToIntsLittleEndian(byte[] bytes) {
        int[] ints = new int[bytes.length / 4];
        for (int i = 0; i < ints.length; i++) {
            ints[i] = ((bytes[i * 4 + 0] & 0xFF)) |
                    ((bytes[i * 4 + 1] & 0xFF) << 8) |
                    ((bytes[i * 4 + 2] & 0xFF) << 16) |
                    ((bytes[i * 4 + 3] & 0xFF) << 24);
        }
        return ints;
    }

    private static int[] blake3Ints(byte[] data, int outLen) {
        byte[] result = blake3(data);
        if (outLen <= 32) {
            return bytesToIntsLittleEndian(Arrays.copyOf(result, outLen));
        }
        return bytesToIntsLittleEndian(result);
    }

    private static long[] getSaltHash() {
        final SaltHolder currHolder = saltHolderLocal.get();

        return currHolder.cachedSaltHash;
    }

    private static long[] hashWorldSeedInternal(long[] worldSeed) {
        byte[] seedBytes = new byte[worldSeed.length * 8];
        ByteBuffer buffer = ByteBuffer.wrap(seedBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (long l : worldSeed) {
            buffer.putLong(l);
        }

        byte[] hashBytes = blake3(seedBytes);
        int[] hashInts = bytesToIntsLittleEndian(hashBytes);

        long[] result = new long[8];
        for (int i = 0; i < 8; i++) {
            result[i] = hashInts[i] & 0xFFFFFFFFL;
        }
        return result;
    }

    public static long[] hashWorldSeed(long[] worldSeed) {
        if (!SecureSeedConfig.enabled && SecureSeedConfig.version == 2) {
            return worldSeed.clone();
        }

        long[] saltHashValue = getSaltHash();
        long[] saltedSeed = new long[worldSeed.length];

        for (int i = 0; i < worldSeed.length; i++) {
            saltedSeed[i] = worldSeed[i] ^ saltHashValue[i % saltHashValue.length];
        }

        return hashWorldSeedInternal(saltedSeed);
    }

    public static long[] expandLevelSeedTo1024Bits(long levelSeed) {
        if (!SecureSeedConfig.enabled && SecureSeedConfig.version == 2) {
            long[] result = new long[Globals.WORLD_SEED_LONGS];
            for (int i = 0; i < Globals.WORLD_SEED_LONGS; i++) {
                result[i] = levelSeed ^ (i * 0x9E3779B97F4A7C15L);
            }
            return result;
        }

        String salt = SecureSeedConfig.salt;
        byte[] saltBytes = salt.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] saltKey = blake3(saltBytes);

        long[] result = new long[Globals.WORLD_SEED_LONGS];

        for (int segment = 0; segment < Globals.WORLD_SEED_LONGS; segment++) {
            byte[] input = new byte[16];
            ByteBuffer buf = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(0, levelSeed);
            buf.putLong(8, segment);

            byte[] hash = blake3Keyed(input, saltKey, 32);
            int[] hashInts = bytesToIntsLittleEndian(hash);

            result[segment] = ((long) hashInts[0] & 0xFFFFFFFFL) |
                    (((long) hashInts[1] & 0xFFFFFFFFL) << 32);
        }

        return result;
    }

    public static long getTerrainSeed(long[] hashedSeed, TerrainType type) {
        return hashedSeed[type.ordinal() % hashedSeed.length];
    }

    public enum TerrainType {
        BASE_TERRAIN,
        BIOME_NOISE,
        CLIMATE,
        AQUIFER,
        ORE,
        SURFACE,
        VEGETATION,
        SHIFT
    }

    public static void hash(long[] message, long[] output, long[] state, int outputBytes, boolean finalBlock) {
        int len = message.length * 8;
        byte[] buffer = threadBuffer.get();
        if (buffer.length < len) {
            buffer = new byte[len];
            threadBuffer.set(buffer);
        }

        ByteBuffer buf = threadByteBuffer.get();
        if (buf.capacity() < len) {
            buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
            threadByteBuffer.set(buf);
        }
        buf.clear();

        for (long l : message) {
            buf.putLong(l);
        }

        byte[] hashBytes = blake3(Arrays.copyOf(buffer, len));
        int[] hashInts = bytesToIntsLittleEndian(hashBytes);

        for (int i = 0; i < Math.min(hashInts.length, output.length); i++) {
            output[i] = hashInts[i] & 0xFFFFFFFFL;
        }
    }
}
