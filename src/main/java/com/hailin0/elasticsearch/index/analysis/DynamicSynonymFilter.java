package com.hailin0.elasticsearch.index.analysis;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.tokenattributes.*;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.util.*;
import org.apache.lucene.util.fst.FST;

import java.io.IOException;
import java.util.Arrays;


/**
 * 静态改为动态，对final属性改造，小心使用，具体看update方法注释
 * SynonymFilter => DynamicSynonymFilter
 *
 * @author hailin0@yeah.net
 * @date 2017-5-9 22:52
 */
public class DynamicSynonymFilter extends TokenFilter {
    public static final String TYPE_SYNONYM = "SYNONYM";
    //private final SynonymMap synonyms;
    private SynonymMap synonyms;
    private final boolean ignoreCase;
    //private final int rollBufferSize;
    private int rollBufferSize;
    private int captureCount;
    private final CharTermAttribute termAtt = (CharTermAttribute) this.addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute posIncrAtt = (PositionIncrementAttribute) this.addAttribute(PositionIncrementAttribute.class);
    private final PositionLengthAttribute posLenAtt = (PositionLengthAttribute) this.addAttribute(PositionLengthAttribute.class);
    private final TypeAttribute typeAtt = (TypeAttribute) this.addAttribute(TypeAttribute.class);
    private final OffsetAttribute offsetAtt = (OffsetAttribute) this.addAttribute(OffsetAttribute.class);
    private int inputSkipCount;
    //private final DynamicSynonymFilter.PendingInput[] futureInputs;
    private DynamicSynonymFilter.PendingInput[] futureInputs;
    private final ByteArrayDataInput bytesReader = new ByteArrayDataInput();
    //private final DynamicSynonymFilter.PendingOutputs[] futureOutputs;
    private DynamicSynonymFilter.PendingOutputs[] futureOutputs;
    private int nextWrite;
    private int nextRead;
    private boolean finished;
    //private final FST.Arc<BytesRef> scratchArc;
    private FST.Arc<BytesRef> scratchArc;
    //private final FST<BytesRef> fst;
    private FST<BytesRef> fst;
    //private final FST.BytesReader fstReader;
    private FST.BytesReader fstReader;
    private final BytesRef scratchBytes = new BytesRef();
    private final CharsRefBuilder scratchChars = new CharsRefBuilder();
    private int lastStartOffset;
    private int lastEndOffset;

    public DynamicSynonymFilter(TokenStream input, SynonymMap synonyms, boolean ignoreCase) {
        super(input);
        this.ignoreCase = ignoreCase;
        update(synonyms);
    }

    private void capture() {
        ++this.captureCount;
        DynamicSynonymFilter.PendingInput input = this.futureInputs[this.nextWrite];
        input.state = this.captureState();
        input.consumed = false;
        input.term.copyChars(this.termAtt.buffer(), 0, this.termAtt.length());
        this.nextWrite = this.rollIncr(this.nextWrite);

        assert this.nextWrite != this.nextRead;

    }

    private void parse() throws IOException {
        assert this.inputSkipCount == 0;

        int curNextRead = this.nextRead;
        BytesRef matchOutput = null;
        int matchInputLength = 0;
        int matchEndOffset = -1;
        BytesRef pendingOutput = (BytesRef) this.fst.outputs.getNoOutput();
        this.fst.getFirstArc(this.scratchArc);

        assert this.scratchArc.output == this.fst.outputs.getNoOutput();

        int tokenCount = 0;

        label91:
        while (true) {
            boolean inputEndOffset = false;
            char[] buffer;
            int bufferLen;
            int var12;
            if (curNextRead == this.nextWrite) {
                if (this.finished) {
                    break;
                }

                assert this.futureInputs[this.nextWrite].consumed;

                if (!this.input.incrementToken()) {
                    this.finished = true;
                    break;
                }

                buffer = this.termAtt.buffer();
                bufferLen = this.termAtt.length();
                DynamicSynonymFilter.PendingInput bufUpto = this.futureInputs[this.nextWrite];
                this.lastStartOffset = bufUpto.startOffset = this.offsetAtt.startOffset();
                this.lastEndOffset = bufUpto.endOffset = this.offsetAtt.endOffset();
                var12 = bufUpto.endOffset;
                if (this.nextRead != this.nextWrite) {
                    this.capture();
                } else {
                    bufUpto.consumed = false;
                }
            } else {
                buffer = this.futureInputs[curNextRead].term.chars();
                bufferLen = this.futureInputs[curNextRead].term.length();
                var12 = this.futureInputs[curNextRead].endOffset;
            }

            ++tokenCount;

            int codePoint;
            for (int var13 = 0; var13 < bufferLen; var13 += Character.charCount(codePoint)) {
                codePoint = Character.codePointAt(buffer, var13, bufferLen);
                if (this.fst.findTargetArc(this.ignoreCase ? Character.toLowerCase(codePoint) : codePoint, this.scratchArc, this.scratchArc, this.fstReader) == null) {
                    break label91;
                }

                pendingOutput = (BytesRef) this.fst.outputs.add(pendingOutput, this.scratchArc.output);
            }

            if (this.scratchArc.isFinal()) {
                matchOutput = (BytesRef) this.fst.outputs.add(pendingOutput, this.scratchArc.nextFinalOutput);
                matchInputLength = tokenCount;
                matchEndOffset = var12;
            }

            if (this.fst.findTargetArc(0, this.scratchArc, this.scratchArc, this.fstReader) == null) {
                break;
            }

            pendingOutput = (BytesRef) this.fst.outputs.add(pendingOutput, this.scratchArc.output);
            if (this.nextRead == this.nextWrite) {
                this.capture();
            }

            curNextRead = this.rollIncr(curNextRead);
        }

        if (this.nextRead == this.nextWrite && !this.finished) {
            this.nextWrite = this.rollIncr(this.nextWrite);
        }

        if (matchOutput != null) {
            this.inputSkipCount = matchInputLength;
            this.addOutput(matchOutput, matchInputLength, matchEndOffset);
        } else if (this.nextRead != this.nextWrite) {
            this.inputSkipCount = 1;
        } else {
            assert this.finished;
        }

    }

    private void addOutput(BytesRef bytes, int matchInputLength, int matchEndOffset) {
        this.bytesReader.reset(bytes.bytes, bytes.offset, bytes.length);
        int code = this.bytesReader.readVInt();
        boolean keepOrig = (code & 1) == 0;
        int count = code >>> 1;

        int upto;
        int idx;
        for (upto = 0; upto < count; ++upto) {
            this.synonyms.words.get(this.bytesReader.readVInt(), this.scratchBytes);
            this.scratchChars.copyUTF8Bytes(this.scratchBytes);
            idx = 0;
            int chEnd = idx + this.scratchChars.length();
            int outputUpto = this.nextRead;

            for (int chIDX = idx; chIDX <= chEnd; ++chIDX) {
                if (chIDX == chEnd || this.scratchChars.charAt(chIDX) == 0) {
                    int outputLen = chIDX - idx;

                    assert outputLen > 0 : "output contains empty string: " + this.scratchChars;

                    int endOffset;
                    int posLen;
                    if (chIDX == chEnd && idx == 0) {
                        endOffset = matchEndOffset;
                        posLen = keepOrig ? matchInputLength : 1;
                    } else {
                        endOffset = -1;
                        posLen = 1;
                    }

                    this.futureOutputs[outputUpto].add(this.scratchChars.chars(), idx, outputLen, endOffset, posLen);
                    idx = 1 + chIDX;
                    outputUpto = this.rollIncr(outputUpto);

                    assert this.futureOutputs[outputUpto].posIncr == 1 : "outputUpto=" + outputUpto + " vs nextWrite=" + this.nextWrite;
                }
            }
        }

        upto = this.nextRead;

        for (idx = 0; idx < matchInputLength; ++idx) {
            this.futureInputs[upto].keepOrig |= keepOrig;
            this.futureInputs[upto].matched = true;
            upto = this.rollIncr(upto);
        }

    }

    private int rollIncr(int count) {
        ++count;
        return count == this.rollBufferSize ? 0 : count;
    }

    int getCaptureCount() {
        return this.captureCount;
    }

    public boolean incrementToken() throws IOException {
        while (true) {
            if (this.inputSkipCount == 0) {
                if (this.finished && this.nextRead == this.nextWrite) {
                    DynamicSynonymFilter.PendingOutputs var6 = this.futureOutputs[this.nextRead];
                    if (var6.upto < var6.count) {
                        int var7 = var6.posIncr;
                        CharsRef var8 = var6.pullNext();
                        this.futureInputs[this.nextRead].reset();
                        if (var6.count == 0) {
                            this.nextWrite = this.nextRead = this.rollIncr(this.nextRead);
                        }

                        this.clearAttributes();
                        this.offsetAtt.setOffset(this.lastStartOffset, this.lastEndOffset);
                        this.termAtt.copyBuffer(var8.chars, var8.offset, var8.length);
                        this.typeAtt.setType("SYNONYM");
                        this.posIncrAtt.setPositionIncrement(var7);
                        return true;
                    }

                    return false;
                }

                this.parse();
            } else {
                DynamicSynonymFilter.PendingInput outputs = this.futureInputs[this.nextRead];
                DynamicSynonymFilter.PendingOutputs posIncr = this.futureOutputs[this.nextRead];
                if (!outputs.consumed && (outputs.keepOrig || !outputs.matched)) {
                    if (outputs.state != null) {
                        this.restoreState(outputs.state);
                    } else {
                        assert this.inputSkipCount == 1 : "inputSkipCount=" + this.inputSkipCount + " nextRead=" + this.nextRead;
                    }

                    outputs.reset();
                    if (posIncr.count > 0) {
                        posIncr.posIncr = 0;
                    } else {
                        this.nextRead = this.rollIncr(this.nextRead);
                        --this.inputSkipCount;
                    }

                    return true;
                }

                if (posIncr.upto < posIncr.count) {
                    outputs.reset();
                    int output = posIncr.posIncr;
                    CharsRef output1 = posIncr.pullNext();
                    this.clearAttributes();
                    this.termAtt.copyBuffer(output1.chars, output1.offset, output1.length);
                    this.typeAtt.setType("SYNONYM");
                    int endOffset = posIncr.getLastEndOffset();
                    if (endOffset == -1) {
                        endOffset = outputs.endOffset;
                    }

                    this.offsetAtt.setOffset(outputs.startOffset, endOffset);
                    this.posIncrAtt.setPositionIncrement(output);
                    this.posLenAtt.setPositionLength(posIncr.getLastPosLength());
                    if (posIncr.count == 0) {
                        this.nextRead = this.rollIncr(this.nextRead);
                        --this.inputSkipCount;
                    }

                    return true;
                }

                outputs.reset();
                this.nextRead = this.rollIncr(this.nextRead);
                --this.inputSkipCount;
            }
        }
    }

    public void reset() throws IOException {
        super.reset();
        this.captureCount = 0;
        this.finished = false;
        this.inputSkipCount = 0;
        this.nextRead = this.nextWrite = 0;
        DynamicSynonymFilter.PendingInput[] var1 = this.futureInputs;
        int var2 = var1.length;

        int var3;
        for (var3 = 0; var3 < var2; ++var3) {
            DynamicSynonymFilter.PendingInput output = var1[var3];
            output.reset();
        }

        DynamicSynonymFilter.PendingOutputs[] var5 = this.futureOutputs;
        var2 = var5.length;

        for (var3 = 0; var3 < var2; ++var3) {
            DynamicSynonymFilter.PendingOutputs var6 = var5[var3];
            var6.reset();
        }

    }

    private static class PendingOutputs {
        CharsRefBuilder[] outputs = new CharsRefBuilder[1];
        int[] endOffsets = new int[1];
        int[] posLengths = new int[1];
        int upto;
        int count;
        int posIncr = 1;
        int lastEndOffset;
        int lastPosLength;

        public PendingOutputs() {
        }

        public void reset() {
            this.upto = this.count = 0;
            this.posIncr = 1;
        }

        public CharsRef pullNext() {
            assert this.upto < this.count;

            this.lastEndOffset = this.endOffsets[this.upto];
            this.lastPosLength = this.posLengths[this.upto];
            CharsRefBuilder result = this.outputs[this.upto++];
            this.posIncr = 0;
            if (this.upto == this.count) {
                this.reset();
            }

            return result.get();
        }

        public int getLastEndOffset() {
            return this.lastEndOffset;
        }

        public int getLastPosLength() {
            return this.lastPosLength;
        }

        public void add(char[] output, int offset, int len, int endOffset, int posLength) {
            if (this.count == this.outputs.length) {
                this.outputs = (CharsRefBuilder[]) Arrays.copyOf(this.outputs, ArrayUtil.oversize(1 + this.count, RamUsageEstimator.NUM_BYTES_OBJECT_REF));
            }

            int[] next;
            if (this.count == this.endOffsets.length) {
                next = new int[ArrayUtil.oversize(1 + this.count, 4)];
                System.arraycopy(this.endOffsets, 0, next, 0, this.count);
                this.endOffsets = next;
            }

            if (this.count == this.posLengths.length) {
                next = new int[ArrayUtil.oversize(1 + this.count, 4)];
                System.arraycopy(this.posLengths, 0, next, 0, this.count);
                this.posLengths = next;
            }

            if (this.outputs[this.count] == null) {
                this.outputs[this.count] = new CharsRefBuilder();
            }

            this.outputs[this.count].copyChars(output, offset, len);
            this.endOffsets[this.count] = endOffset;
            this.posLengths[this.count] = posLength;
            ++this.count;
        }
    }

    private static class PendingInput {
        final CharsRefBuilder term;
        State state;
        boolean keepOrig;
        boolean matched;
        boolean consumed;
        int startOffset;
        int endOffset;

        private PendingInput() {
            this.term = new CharsRefBuilder();
            this.consumed = true;
        }

        public void reset() {
            this.state = null;
            this.consumed = true;
            this.keepOrig = false;
            this.matched = false;
        }
    }




    /**
     * 增加update逻辑,此方法中所有赋值的属性皆为final改造，注意只能在此方法中使用，否则可能导致bug
     *
     * @param synonymMap
     */
    public void update(SynonymMap synonymMap) {
        this.synonyms = synonymMap;
        this.fst = synonyms.fst;
        if (this.fst == null) {
            throw new IllegalArgumentException("fst must be non-null");
        } else {
            this.fstReader = this.fst.getBytesReader();
            this.rollBufferSize = 1 + synonyms.maxHorizontalContext;
            this.futureInputs = new DynamicSynonymFilter.PendingInput[this.rollBufferSize];
            this.futureOutputs = new DynamicSynonymFilter.PendingOutputs[this.rollBufferSize];

            for (int pos = 0; pos < this.rollBufferSize; ++pos) {
                this.futureInputs[pos] = new DynamicSynonymFilter.PendingInput();
                this.futureOutputs[pos] = new DynamicSynonymFilter.PendingOutputs();
            }

            this.scratchArc = new FST.Arc();
        }
    }
}