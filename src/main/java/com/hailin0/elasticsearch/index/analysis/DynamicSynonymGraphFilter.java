package com.hailin0.elasticsearch.index.analysis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRefBuilder;
import org.apache.lucene.util.RollingBuffer;
import org.apache.lucene.util.AttributeSource.State;
import org.apache.lucene.util.RollingBuffer.Resettable;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.FST.Arc;
import org.apache.lucene.util.fst.FST.BytesReader;


/**
 * 静态改为动态，对final属性改造，小心使用，具体看update方法注释
 * SynonymGraphFilter => DynamicSynonymGraphFilter
 *
 * @author hailin0@yeah.net
 * @date 2017-5-9 22:52
 */
public class DynamicSynonymGraphFilter extends TokenFilter implements SynonymDynamicSupport{
    public static final String TYPE_SYNONYM = "SYNONYM";
    private final CharTermAttribute termAtt = (CharTermAttribute)this.addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute posIncrAtt = (PositionIncrementAttribute)this.addAttribute(PositionIncrementAttribute.class);
    private final PositionLengthAttribute posLenAtt = (PositionLengthAttribute)this.addAttribute(PositionLengthAttribute.class);
    private final TypeAttribute typeAtt = (TypeAttribute)this.addAttribute(TypeAttribute.class);
    private final OffsetAttribute offsetAtt = (OffsetAttribute)this.addAttribute(OffsetAttribute.class);
    //private final SynonymMap synonyms;
    private SynonymMap synonyms;
    private final boolean ignoreCase;
    //private final FST<BytesRef> fst;
    private FST<BytesRef> fst;
    //private final FST.BytesReader fstReader;
    private FST.BytesReader fstReader;
    //private final FST.Arc<BytesRef> scratchArc;
    private FST.Arc<BytesRef> scratchArc;
    private final ByteArrayDataInput bytesReader = new ByteArrayDataInput();
    private final BytesRef scratchBytes = new BytesRef();
    private final CharsRefBuilder scratchChars = new CharsRefBuilder();
    private final LinkedList<DynamicSynonymGraphFilter.BufferedOutputToken> outputBuffer = new LinkedList();
    private int nextNodeOut;
    private int lastNodeOut;
    private int maxLookaheadUsed;
    private int captureCount;
    private boolean liveToken;
    private int matchStartOffset;
    private int matchEndOffset;
    private boolean finished;
    private int lookaheadNextRead;
    private int lookaheadNextWrite;
    private RollingBuffer<DynamicSynonymGraphFilter.BufferedInputToken> lookahead = new RollingBuffer() {
        protected DynamicSynonymGraphFilter.BufferedInputToken newInstance() {
            return new DynamicSynonymGraphFilter.BufferedInputToken();
        }
    };

    public DynamicSynonymGraphFilter(TokenStream input, SynonymMap synonyms, boolean ignoreCase) {
        super(input);
        this.ignoreCase = ignoreCase;
        update(synonyms);
    }

    public boolean incrementToken() throws IOException {
        assert this.lastNodeOut <= this.nextNodeOut;

        if(!this.outputBuffer.isEmpty()) {
            this.releaseBufferedToken();

            assert !this.liveToken;

            return true;
        } else if(this.parse()) {
            this.releaseBufferedToken();

            assert !this.liveToken;

            return true;
        } else {
            if(this.lookaheadNextRead == this.lookaheadNextWrite) {
                if(this.finished) {
                    return false;
                }

                assert this.liveToken;

                this.liveToken = false;
            } else {
                assert this.lookaheadNextRead < this.lookaheadNextWrite : "read=" + this.lookaheadNextRead + " write=" + this.lookaheadNextWrite;

                DynamicSynonymGraphFilter.BufferedInputToken token = (DynamicSynonymGraphFilter.BufferedInputToken)this.lookahead.get(this.lookaheadNextRead);
                ++this.lookaheadNextRead;
                this.restoreState(token.state);
                this.lookahead.freeBefore(this.lookaheadNextRead);

                assert !this.liveToken;
            }

            this.lastNodeOut += this.posIncrAtt.getPositionIncrement();
            this.nextNodeOut = this.lastNodeOut + this.posLenAtt.getPositionLength();
            return true;
        }
    }

    private void releaseBufferedToken() throws IOException {
        DynamicSynonymGraphFilter.BufferedOutputToken token = (DynamicSynonymGraphFilter.BufferedOutputToken)this.outputBuffer.pollFirst();
        if(token.state != null) {
            this.restoreState(token.state);
        } else {
            this.clearAttributes();
            this.termAtt.append(token.term);

            assert this.matchStartOffset != -1;

            this.offsetAtt.setOffset(this.matchStartOffset, this.matchEndOffset);
            this.typeAtt.setType("SYNONYM");
        }

        this.posIncrAtt.setPositionIncrement(token.startNode - this.lastNodeOut);
        this.lastNodeOut = token.startNode;
        this.posLenAtt.setPositionLength(token.endNode - token.startNode);
    }

    private boolean parse() throws IOException {
        BytesRef matchOutput = null;
        int matchInputLength = 0;
        BytesRef pendingOutput = (BytesRef)this.fst.outputs.getNoOutput();
        this.fst.getFirstArc(this.scratchArc);

        assert this.scratchArc.output == this.fst.outputs.getNoOutput();

        int matchLength = 0;
        boolean doFinalCapture = false;
        int lookaheadUpto = this.lookaheadNextRead;
        this.matchStartOffset = -1;

        label93:
        while(true) {
            char[] buffer;
            int bufferLen;
            int inputEndOffset;
            if(lookaheadUpto <= this.lookahead.getMaxPos()) {
                DynamicSynonymGraphFilter.BufferedInputToken bufUpto = (DynamicSynonymGraphFilter.BufferedInputToken)this.lookahead.get(lookaheadUpto);
                ++lookaheadUpto;
                buffer = bufUpto.term.chars();
                bufferLen = bufUpto.term.length();
                inputEndOffset = bufUpto.endOffset;
                if(this.matchStartOffset == -1) {
                    this.matchStartOffset = bufUpto.startOffset;
                }
            } else {
                assert this.finished || !this.liveToken;

                if(this.finished) {
                    break;
                }

                if(!this.input.incrementToken()) {
                    this.finished = true;
                    break;
                }

                this.liveToken = true;
                buffer = this.termAtt.buffer();
                bufferLen = this.termAtt.length();
                if(this.matchStartOffset == -1) {
                    this.matchStartOffset = this.offsetAtt.startOffset();
                }

                inputEndOffset = this.offsetAtt.endOffset();
                ++lookaheadUpto;
            }

            ++matchLength;

            int codePoint;
            int var12;
            for(var12 = 0; var12 < bufferLen; var12 += Character.charCount(codePoint)) {
                codePoint = Character.codePointAt(buffer, var12, bufferLen);
                if(this.fst.findTargetArc(this.ignoreCase?Character.toLowerCase(codePoint):codePoint, this.scratchArc, this.scratchArc, this.fstReader) == null) {
                    break label93;
                }

                pendingOutput = (BytesRef)this.fst.outputs.add(pendingOutput, this.scratchArc.output);
            }

            assert var12 == bufferLen;

            if(this.scratchArc.isFinal()) {
                matchOutput = (BytesRef)this.fst.outputs.add(pendingOutput, this.scratchArc.nextFinalOutput);
                matchInputLength = matchLength;
                this.matchEndOffset = inputEndOffset;
            }

            if(this.fst.findTargetArc(0, this.scratchArc, this.scratchArc, this.fstReader) == null) {
                break;
            }

            pendingOutput = (BytesRef)this.fst.outputs.add(pendingOutput, this.scratchArc.output);
            doFinalCapture = true;
            if(this.liveToken) {
                this.capture();
            }
        }

        if(doFinalCapture && this.liveToken && !this.finished) {
            this.capture();
        }

        if(matchOutput != null) {
            if(this.liveToken) {
                this.capture();
            }

            this.bufferOutputTokens(matchOutput, matchInputLength);
            this.lookaheadNextRead += matchInputLength;
            this.lookahead.freeBefore(this.lookaheadNextRead);
            return true;
        } else {
            return false;
        }
    }

    private void bufferOutputTokens(BytesRef bytes, int matchInputLength) {
        this.bytesReader.reset(bytes.bytes, bytes.offset, bytes.length);
        int code = this.bytesReader.readVInt();
        boolean keepOrig = (code & 1) == 0;
        int totalPathNodes;
        if(keepOrig) {
            assert matchInputLength > 0;

            totalPathNodes = matchInputLength - 1;
        } else {
            totalPathNodes = 0;
        }

        int count = code >>> 1;
        ArrayList paths = new ArrayList();

        int startNode;
        int endNode;
        int newNodeCount;
        int token;
        int token1;
        for(startNode = 0; startNode < count; ++startNode) {
            endNode = this.bytesReader.readVInt();
            this.synonyms.words.get(endNode, this.scratchBytes);
            this.scratchChars.copyUTF8Bytes(this.scratchBytes);
            newNodeCount = 0;
            ArrayList lastNode = new ArrayList();
            paths.add(lastNode);
            token = this.scratchChars.length();

            for(token1 = 0; token1 <= token; ++token1) {
                if(token1 == token || this.scratchChars.charAt(token1) == 0) {
                    lastNode.add(new String(this.scratchChars.chars(), newNodeCount, token1 - newNodeCount));
                    newNodeCount = 1 + token1;
                }
            }

            assert lastNode.size() > 0;

            totalPathNodes += lastNode.size() - 1;
        }

        startNode = this.nextNodeOut;
        endNode = startNode + totalPathNodes + 1;
        newNodeCount = 0;

        List var18;
        for(Iterator var15 = paths.iterator(); var15.hasNext(); this.outputBuffer.add(new DynamicSynonymGraphFilter.BufferedOutputToken((State)null, (String)var18.get(0), startNode, token1))) {
            var18 = (List)var15.next();
            if(var18.size() == 1) {
                token1 = endNode;
            } else {
                token1 = this.nextNodeOut + newNodeCount + 1;
                newNodeCount += var18.size() - 1;
            }
        }

        if(keepOrig) {
            DynamicSynonymGraphFilter.BufferedInputToken var16 = (DynamicSynonymGraphFilter.BufferedInputToken)this.lookahead.get(this.lookaheadNextRead);
            if(matchInputLength == 1) {
                token = endNode;
            } else {
                token = this.nextNodeOut + newNodeCount + 1;
            }

            this.outputBuffer.add(new DynamicSynonymGraphFilter.BufferedOutputToken(var16.state, var16.term.toString(), startNode, token));
        }

        this.nextNodeOut = endNode;

        int var17;
        for(var17 = 0; var17 < paths.size(); ++var17) {
            var18 = (List)paths.get(var17);
            if(var18.size() > 1) {
                token1 = ((DynamicSynonymGraphFilter.BufferedOutputToken)this.outputBuffer.get(var17)).endNode;

                for(int i = 1; i < var18.size() - 1; ++i) {
                    this.outputBuffer.add(new DynamicSynonymGraphFilter.BufferedOutputToken((State)null, (String)var18.get(i), token1, token1 + 1));
                    ++token1;
                }

                this.outputBuffer.add(new DynamicSynonymGraphFilter.BufferedOutputToken((State)null, (String)var18.get(var18.size() - 1), token1, endNode));
            }
        }

        if(keepOrig && matchInputLength > 1) {
            var17 = ((DynamicSynonymGraphFilter.BufferedOutputToken)this.outputBuffer.get(paths.size())).endNode;

            for(token = 1; token < matchInputLength - 1; ++token) {
                DynamicSynonymGraphFilter.BufferedInputToken var20 = (DynamicSynonymGraphFilter.BufferedInputToken)this.lookahead.get(this.lookaheadNextRead + token);
                this.outputBuffer.add(new DynamicSynonymGraphFilter.BufferedOutputToken(var20.state, var20.term.toString(), var17, var17 + 1));
                ++var17;
            }

            DynamicSynonymGraphFilter.BufferedInputToken var19 = (DynamicSynonymGraphFilter.BufferedInputToken)this.lookahead.get(this.lookaheadNextRead + matchInputLength - 1);
            this.outputBuffer.add(new DynamicSynonymGraphFilter.BufferedOutputToken(var19.state, var19.term.toString(), var17, endNode));
        }

    }

    private void capture() {
        assert this.liveToken;

        this.liveToken = false;
        DynamicSynonymGraphFilter.BufferedInputToken token = (DynamicSynonymGraphFilter.BufferedInputToken)this.lookahead.get(this.lookaheadNextWrite);
        ++this.lookaheadNextWrite;
        token.state = this.captureState();
        token.startOffset = this.offsetAtt.startOffset();
        token.endOffset = this.offsetAtt.endOffset();

        assert token.term.length() == 0;

        token.term.append(this.termAtt);
        ++this.captureCount;
        this.maxLookaheadUsed = Math.max(this.maxLookaheadUsed, this.lookahead.getBufferSize());
    }

    public void reset() throws IOException {
        super.reset();
        this.lookahead.reset();
        this.lookaheadNextWrite = 0;
        this.lookaheadNextRead = 0;
        this.captureCount = 0;
        this.lastNodeOut = -1;
        this.nextNodeOut = 0;
        this.matchStartOffset = -1;
        this.matchEndOffset = -1;
        this.finished = false;
        this.liveToken = false;
        this.outputBuffer.clear();
        this.maxLookaheadUsed = 0;
    }

    int getCaptureCount() {
        return this.captureCount;
    }

    int getMaxLookaheadUsed() {
        return this.maxLookaheadUsed;
    }

    static class BufferedOutputToken {
        final String term;
        final State state;
        final int startNode;
        final int endNode;

        public BufferedOutputToken(State state, String term, int startNode, int endNode) {
            this.state = state;
            this.term = term;
            this.startNode = startNode;
            this.endNode = endNode;
        }
    }

    static class BufferedInputToken implements RollingBuffer.Resettable {
        final CharsRefBuilder term = new CharsRefBuilder();
        State state;
        int startOffset = -1;
        int endOffset = -1;

        BufferedInputToken() {
        }

        public void reset() {
            this.state = null;
            this.term.clear();
            this.startOffset = -1;
            this.endOffset = -1;
        }
    }


    /**
     * 增加update逻辑,此方法中所有赋值的属性皆为final改造，注意只能在此方法中使用，否则可能导致bug
     *
     * @param synonymMap
     */
    @Override
    public void update(SynonymMap synonymMap) {
        this.synonyms = synonymMap;
        this.fst = synonyms.fst;
        if(this.fst == null) {
            throw new IllegalArgumentException("fst must be non-null");
        } else {
            this.fstReader = this.fst.getBytesReader();
            this.scratchArc = new FST.Arc();
            //this.ignoreCase = ignoreCase;
        }
    }
}