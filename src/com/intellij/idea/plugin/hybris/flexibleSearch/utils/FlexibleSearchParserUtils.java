/*
 * This file is part of "hybris integration" plugin for Intellij IDEA.
 * Copyright (C) 2014-2016 Alexander Bartash <AlexanderBartash@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.intellij.idea.plugin.hybris.flexibleSearch.utils;

import com.intellij.codeInsight.completion.impl.CamelHumpMatcher;
import com.intellij.lang.ASTNode;
import com.intellij.lang.BracePair;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageBraceMatching;
import com.intellij.lang.LighterASTNode;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.lang.WhitespacesAndCommentsBinder;
import com.intellij.lang.WhitespacesBinders;
import com.intellij.lang.impl.PsiBuilderAdapter;
import com.intellij.lang.impl.PsiBuilderImpl;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringHash;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.TokenType;
import com.intellij.psi.impl.source.resolve.FileContextUtil;
import com.intellij.psi.impl.source.tree.CompositePsiElement;
import com.intellij.psi.tree.ICompositeElementType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.util.Function;
import com.intellij.util.PairProcessor;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.LimitedPool;
import gnu.trove.THashSet;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;

import static com.intellij.openapi.util.text.StringUtil.first;
import static com.intellij.openapi.util.text.StringUtil.isJavaIdentifierStart;
import static com.intellij.openapi.util.text.StringUtil.isNotEmpty;
import static com.intellij.openapi.util.text.StringUtil.isWhiteSpace;
import static com.intellij.openapi.util.text.StringUtil.join;
import static com.intellij.openapi.util.text.StringUtil.notNullize;
import static com.intellij.openapi.util.text.StringUtil.parseInt;
import static com.intellij.openapi.util.text.StringUtil.startsWithIgnoreCase;
/**
 * Created 1:01 PM 31 May 2015
 *
 * @author Alexander Bartash <AlexanderBartash@gmail.com>
 *
 *
 *     This class is just a temporary solution to disable FS error highlighting completely.
 *     It'll get reverted when we implement FS properly.
 */
public class FlexibleSearchParserUtils {
    private static final Logger LOG = Logger.getInstance("com.intellij.idea.plugin.hybris.flexibleSearch.utils.FlexibleSearchParserUtils");

    private static final int MAX_RECURSION_LEVEL = parseInt(System.getProperty("grammar.kit.gpub.max.level"), 1000);
    private static final int MAX_VARIANTS_SIZE = 10000;
    private static final int MAX_VARIANTS_TO_DISPLAY = 50;
    private static final int MAX_ERROR_TOKEN_TEXT = 20;

    private static final int INITIAL_VARIANTS_SIZE = 1000;
    private static final int VARIANTS_POOL_SIZE = 10000;
    private static final int FRAMES_POOL_SIZE = 500;

    public static final IElementType DUMMY_BLOCK = new FlexibleSearchParserUtils.DummyBlockElementType();

    public interface Parser {
        boolean parse(PsiBuilder builder, int level);
    }

    public static final FlexibleSearchParserUtils.Parser TOKEN_ADVANCER = new FlexibleSearchParserUtils.Parser() {
        @Override
        public boolean parse(PsiBuilder builder, int level) {
            if (builder.eof()) return false;
            builder.advanceLexer();
            return true;
        }
    };

    public static final FlexibleSearchParserUtils.Parser TRUE_CONDITION = new FlexibleSearchParserUtils.Parser() {
        @Override
        public boolean parse(PsiBuilder builder, int level) {
            return true;
        }
    };

    public interface Hook<T> {

        @Contract("_,null,_->null")
        PsiBuilder.Marker run(PsiBuilder builder, PsiBuilder.Marker marker, T param);

    }

    public static final FlexibleSearchParserUtils.Hook<WhitespacesAndCommentsBinder> LEFT_BINDER =
        new FlexibleSearchParserUtils.Hook<WhitespacesAndCommentsBinder>() {
            @Override
            public PsiBuilder.Marker run(PsiBuilder builder,
                                         PsiBuilder.Marker marker,
                                         WhitespacesAndCommentsBinder param) {
                if (marker != null) marker.setCustomEdgeTokenBinders(param, null);
                return marker;
            }
        };

    public static final FlexibleSearchParserUtils.Hook<WhitespacesAndCommentsBinder> RIGHT_BINDER =
        new FlexibleSearchParserUtils.Hook<WhitespacesAndCommentsBinder>() {
            @Override
            public PsiBuilder.Marker run(PsiBuilder builder,
                                         PsiBuilder.Marker marker,
                                         WhitespacesAndCommentsBinder param) {
                if (marker != null) marker.setCustomEdgeTokenBinders(null, param);
                return marker;
            }
        };

    public static final FlexibleSearchParserUtils.Hook<WhitespacesAndCommentsBinder[]> WS_BINDERS =
        new FlexibleSearchParserUtils.Hook<WhitespacesAndCommentsBinder[]>() {
            @Override
            public PsiBuilder.Marker run(PsiBuilder builder,
                                         PsiBuilder.Marker marker,
                                         WhitespacesAndCommentsBinder[] param) {
                if (marker != null) marker.setCustomEdgeTokenBinders(param[0], param[1]);
                return marker;
            }
        };

    public static final FlexibleSearchParserUtils.Hook<String> LOG_HOOK = new FlexibleSearchParserUtils.Hook<String>() {
        @Override
        public PsiBuilder.Marker run(PsiBuilder builder, PsiBuilder.Marker marker, String param) {
            PsiBuilderImpl.ProductionMarker m = (PsiBuilderImpl.ProductionMarker)marker;
            int start = m == null ? builder.getCurrentOffset() : m.getStartOffset();
            int end = m == null ? start : m.getEndOffset();
            String prefix = "[" + start + ", " + end + "]" + (m == null ? "" : " " + m.getTokenType());
            builder.mark().error(prefix + ": " + param);
            return marker;
        }
    };


    public static boolean eof(PsiBuilder builder, int level) {
        return builder.eof();
    }

    public static int current_position_(PsiBuilder builder) {
        return builder.rawTokenIndex();
    }

    public static boolean recursion_guard_(PsiBuilder builder, int level, String funcName) {
        if (level > MAX_RECURSION_LEVEL) {
            builder.mark().error("Maximum recursion level (" + MAX_RECURSION_LEVEL + ") reached in '" + funcName + "'");
            return false;
        }
        return true;
    }

    public static boolean empty_element_parsed_guard_(PsiBuilder builder, String funcName, int pos) {
        if (pos == current_position_(builder)) {
            // sometimes this is a correct situation, therefore no explicit marker
            builder.error("Empty element parsed in '" + funcName + "' at offset " + builder.getCurrentOffset());
            return false;
        }
        return true;
    }

    public static boolean invalid_left_marker_guard_(PsiBuilder builder, PsiBuilder.Marker marker, String funcName) {
        //builder.error("Invalid left marker encountered in " + funcName_ +" at offset " + builder.getCurrentOffset());
        boolean goodMarker = marker != null; // && ((LighterASTNode)marker).getTokenType() != TokenType.ERROR_ELEMENT;
        if (!goodMarker) return false;
        FlexibleSearchParserUtils.ErrorState state = FlexibleSearchParserUtils.ErrorState.get(builder);

        return state.currentFrame != null;
    }

    public static TokenSet create_token_set_(IElementType... tokenTypes) {
        return TokenSet.create(tokenTypes);
    }

    public static boolean leftMarkerIs(PsiBuilder builder, IElementType type) {
        LighterASTNode marker = builder.getLatestDoneMarker();
        return marker != null && marker.getTokenType() == type;
    }

    private static boolean consumeTokens(PsiBuilder builder, boolean smart, int pin, IElementType... tokens) {
        FlexibleSearchParserUtils.ErrorState state = FlexibleSearchParserUtils.ErrorState.get(builder);
        if (state.completionState != null && state.predicateSign) {
            addCompletionVariant(builder, state.completionState, tokens);
        }
        // suppress single token completion
        FlexibleSearchParserUtils.CompletionState completionState = state.completionState;
        state.completionState = null;
        boolean result = true;
        boolean pinned = false;
        for (int i = 0, tokensLength = tokens.length; i < tokensLength; i++) {
            if (pin > 0 && i == pin) pinned = result;
            if (result || pinned) {
                boolean fast = smart && i == 0;
                if (!(fast ? consumeTokenFast(builder, tokens[i]) : consumeToken(builder, tokens[i]))) {
                    result = false;
                    if (pin < 0 || pinned) report_error_(builder, state, false);
                }
            }
        }
        state.completionState = completionState;
        return pinned || result;
    }

    public static boolean consumeTokens(PsiBuilder builder, int pin, IElementType... token) {
        return consumeTokens(builder, false, pin, token);
    }

    public static boolean consumeTokensSmart(PsiBuilder builder, int pin, IElementType... token) {
        return consumeTokens(builder, true, pin, token);
    }

    public static boolean parseTokens(PsiBuilder builder, int pin, IElementType... tokens) {
        return parseTokens(builder, false, pin, tokens);
    }

    public static boolean parseTokensSmart(PsiBuilder builder, int pin, IElementType... tokens) {
        return parseTokens(builder, true, pin, tokens);
    }

    public static boolean parseTokens(PsiBuilder builder, boolean smart, int pin, IElementType... tokens) {
        PsiBuilder.Marker marker = builder.mark();
        boolean result = consumeTokens(builder, smart, pin, tokens);
        if (!result) {
            marker.rollbackTo();
        }
        else {
            marker.drop();
        }
        return result;
    }

    public static boolean consumeTokenSmart(PsiBuilder builder, IElementType token) {
        addCompletionVariantSmart(builder, token);
        return consumeTokenFast(builder, token);
    }

    public static boolean consumeTokenSmart(PsiBuilder builder, String token) {
        addCompletionVariantSmart(builder, token);
        return consumeTokenFast(builder, token);
    }

    public static boolean consumeToken(PsiBuilder builder, IElementType token) {
        addVariantSmart(builder, token, true);
        if (nextTokenIsFast(builder, token)) {
            builder.advanceLexer();
            return true;
        }
        return false;
    }

    public static boolean consumeTokenFast(PsiBuilder builder, IElementType token) {
        if (nextTokenIsFast(builder, token)) {
            builder.advanceLexer();
            return true;
        }
        return false;
    }

    public static boolean consumeToken(PsiBuilder builder, String text) {
        return consumeToken(builder, text, FlexibleSearchParserUtils.ErrorState.get(builder).caseSensitive);
    }

    public static boolean consumeToken(PsiBuilder builder, String text, boolean caseSensitive) {
        addVariantSmart(builder, text, true);
        int count = nextTokenIsFast(builder, text, caseSensitive);
        if (count > 0) {
            while (count-- > 0) builder.advanceLexer();
            return true;
        }
        return false;
    }

    public static boolean consumeTokenFast(PsiBuilder builder, String text) {
        int count = nextTokenIsFast(builder, text, FlexibleSearchParserUtils.ErrorState.get(builder).caseSensitive);
        if (count > 0) {
            while (count-- > 0) builder.advanceLexer();
            return true;
        }
        return false;
    }

    public static boolean nextTokenIsFast(PsiBuilder builder, IElementType token) {
        return builder.getTokenType() == token;
    }

    public static boolean nextTokenIsFast(PsiBuilder builder, IElementType... tokens) {
        IElementType tokenType = builder.getTokenType();
        for (IElementType token : tokens) {
            if (token == tokenType) return true;
        }
        return false;
    }

    public static boolean nextTokenIsSmart(PsiBuilder builder, IElementType token) {
        return nextTokenIsFast(builder, token) || FlexibleSearchParserUtils.ErrorState.get(builder).completionState != null;
    }

    public static boolean nextTokenIsSmart(PsiBuilder builder, IElementType... tokens) {
        return nextTokenIsFast(builder, tokens) || FlexibleSearchParserUtils.ErrorState.get(builder).completionState != null;
    }

    public static boolean nextTokenIs(PsiBuilder builder, String frameName, IElementType... tokens) {
        FlexibleSearchParserUtils.ErrorState state = FlexibleSearchParserUtils.ErrorState.get(builder);
        if (state.completionState != null) return true;
        boolean track = !state.suppressErrors && state.predicateCount < 2 && state.predicateSign;
        return !track ? nextTokenIsFast(builder, tokens) : nextTokenIsSlow(builder, frameName, tokens);
    }

    public static boolean nextTokenIsSlow(PsiBuilder builder, String frameName, IElementType... tokens) {
        FlexibleSearchParserUtils.ErrorState state = FlexibleSearchParserUtils.ErrorState.get(builder);
        IElementType tokenType = builder.getTokenType();
        if (isNotEmpty(frameName)) {
            addVariantInner(state, builder.rawTokenIndex(), frameName);
        }
        else {
            for (IElementType token : tokens) {
                addVariant(builder, state, token);
            }
        }
        if (tokenType == null) return false;
        for (IElementType token : tokens) {
            if (tokenType == token) return true;
        }
        return false;
    }

    public static boolean nextTokenIs(PsiBuilder builder, IElementType token) {
        if (!addVariantSmart(builder, token, false)) return true;
        return nextTokenIsFast(builder, token);
    }

    public static boolean nextTokenIs(PsiBuilder builder, String tokenText) {
        if (!addVariantSmart(builder, tokenText, false)) return true;
        return nextTokenIsFast(builder, tokenText, FlexibleSearchParserUtils.ErrorState.get(builder).caseSensitive) > 0;
    }

    public static boolean nextTokenIsFast(PsiBuilder builder, String tokenText) {
        return nextTokenIsFast(builder, tokenText, FlexibleSearchParserUtils.ErrorState.get(builder).caseSensitive) > 0;
    }

    public static int nextTokenIsFast(PsiBuilder builder, String tokenText, boolean caseSensitive) {
        CharSequence sequence = builder.getOriginalText();
        int offset = builder.getCurrentOffset();
        int endOffset = offset + tokenText.length();
        CharSequence subSequence = sequence.subSequence(offset, Math.min(endOffset, sequence.length()));

        if (!Comparing.equal(subSequence, tokenText, caseSensitive)) return 0;

        int count = 0;
        while (true) {
            int nextOffset = builder.rawTokenTypeStart(++count);
            if (nextOffset > endOffset) {
                return -count;
            }
            else if (nextOffset == endOffset) {
                break;
            }
        }
        return count;
    }

    private static void addCompletionVariantSmart(PsiBuilder builder, Object token) {
        FlexibleSearchParserUtils.ErrorState state = FlexibleSearchParserUtils.ErrorState.get(builder);
        FlexibleSearchParserUtils.CompletionState completionState = state.completionState;
        if (completionState != null && state.predicateSign) {
            addCompletionVariant(builder, completionState, token);
        }
    }

    private static boolean addVariantSmart(PsiBuilder builder, Object token, boolean force) {
        FlexibleSearchParserUtils.ErrorState state = FlexibleSearchParserUtils.ErrorState.get(builder);
        // skip FIRST check in completion mode
        if (state.completionState != null && !force) return false;
        builder.eof();
        if (!state.suppressErrors && state.predicateCount < 2) {
            addVariant(builder, state, token);
        }
        return true;
    }

    public static void addVariant(PsiBuilder builder, String text) {
        addVariant(builder, FlexibleSearchParserUtils.ErrorState.get(builder), text);
    }

    private static void addVariant(PsiBuilder builder, FlexibleSearchParserUtils.ErrorState state, Object o) {
        builder.eof(); // skip whitespaces
        addVariantInner(state, builder.rawTokenIndex(), o);

        FlexibleSearchParserUtils.CompletionState completionState = state.completionState;
        if (completionState != null && state.predicateSign) {
            addCompletionVariant(builder, completionState, o);
        }
    }

    private static void addVariantInner(FlexibleSearchParserUtils.ErrorState state, int pos, Object o) {
        FlexibleSearchParserUtils.Variant variant = state.VARIANTS.alloc().init(pos, o);
        if (state.predicateSign) {
            state.variants.add(variant);
            if (state.lastExpectedVariantPos < variant.position) {
                state.lastExpectedVariantPos = variant.position;
            }
        }
        else {
            state.unexpected.add(variant);
        }
    }

    private static void addCompletionVariant(@NotNull PsiBuilder builder, @NotNull FlexibleSearchParserUtils.CompletionState completionState, Object o) {
        int offset = builder.getCurrentOffset();
        if (!builder.eof() && offset == builder.rawTokenTypeStart(1)) return; // suppress for zero-length tokens
        String text = completionState.convertItem(o);
        int length = text == null ? 0 : text.length();
        boolean add = length != 0 && completionState.prefixMatches(builder, text);
        add = add && length > 1 && !(text.charAt(0) == '<' && text.charAt(length - 1) == '>') &&
              !(text.charAt(0) == '\'' && text.charAt(length - 1) == '\'' && length < 5);
        if (add) {
            completionState.addItem(builder, text);
        }
    }

    public static boolean isWhitespaceOrComment(@NotNull PsiBuilder builder, @Nullable IElementType type) {
        return ((PsiBuilderImpl)((FlexibleSearchParserUtils.Builder)builder).getDelegate()).whitespaceOrComment(type);
    }

    private static boolean wasAutoSkipped(@NotNull PsiBuilder builder, int steps) {
        for (int i = -1; i >= -steps; i--) {
            if (!isWhitespaceOrComment(builder, builder.rawLookup(i))) return false;
        }
        return true;
    }

    // here's the new section API for compact parsers & less IntelliJ platform API exposure
    public static final int _NONE_       = 0x0;
    public static final int _COLLAPSE_   = 0x1;
    public static final int _LEFT_       = 0x2;
    public static final int _LEFT_INNER_ = 0x4;
    public static final int _AND_        = 0x8;
    public static final int _NOT_        = 0x10;
    public static final int _UPPER_      = 0x20;

    // simple enter/exit methods pair that doesn't require frame object
    public static PsiBuilder.Marker enter_section_(PsiBuilder builder) {
        FlexibleSearchParserUtils.ErrorState.get(builder).level++;
        return builder.mark();
    }

    public static void exit_section_(PsiBuilder builder,
                                     PsiBuilder.Marker marker,
                                     @Nullable IElementType elementType,
                                     boolean result) {
        FlexibleSearchParserUtils.ErrorState state = FlexibleSearchParserUtils.ErrorState.get(builder);
        close_marker_impl_(state.currentFrame, marker, elementType, result);
        run_hooks_impl_(builder, state, result ? elementType : null);
        state.level--;
    }

    // complex enter/exit methods pair with frame object
    public static PsiBuilder.Marker enter_section_(PsiBuilder builder, int level, int modifiers, String frameName) {
        return enter_section_(builder, level, modifiers, null, frameName);
    }

    public static PsiBuilder.Marker enter_section_(PsiBuilder builder, int level, int modifiers) {
        return enter_section_(builder, level, modifiers, null, null);
    }

    public static PsiBuilder.Marker enter_section_(PsiBuilder builder, int level, int modifiers, IElementType elementType, String frameName) {
        PsiBuilder.Marker marker = builder.mark();
        enter_section_impl_(builder, level, modifiers, elementType, frameName);
        return marker;
    }

    private static void enter_section_impl_(PsiBuilder builder, int level, int modifiers, IElementType elementType, String frameName) {
        FlexibleSearchParserUtils.ErrorState state = FlexibleSearchParserUtils.ErrorState.get(builder);
        state.level++;
        FlexibleSearchParserUtils.Frame frame = state.FRAMES.alloc().init(builder, state, level, modifiers, elementType, frameName);
        FlexibleSearchParserUtils.Frame prevFrame = state.currentFrame;
        if (prevFrame != null && prevFrame.errorReportedAt > frame.position) {
            // report error for previous unsuccessful frame
            reportError(builder, state, frame, null, true, false);
        }
        if (((frame.modifiers & _LEFT_) | (frame.modifiers & _LEFT_INNER_)) != 0) {
            PsiBuilder.Marker left = (PsiBuilder.Marker)builder.getLatestDoneMarker();
            if (invalid_left_marker_guard_(builder, left, frameName)) {
                frame.leftMarker = left;
            }
        }
        state.currentFrame = frame;
        if ((modifiers & _AND_) != 0) {
            if (state.predicateCount == 0 && !state.predicateSign) {
                throw new AssertionError("Incorrect false predicate sign");
            }
            state.predicateCount++;
        }
        else if ((modifiers & _NOT_) != 0) {
            state.predicateSign = state.predicateCount != 0 && !state.predicateSign;
            state.predicateCount++;
        }
    }

    public static void exit_section_(PsiBuilder builder,
                                     int level,
                                     PsiBuilder.Marker marker,
                                     boolean result,
                                     boolean pinned,
                                     @Nullable FlexibleSearchParserUtils.Parser eatMore) {
        exit_section_(builder, level, marker, null, result, pinned, eatMore);
    }

    public static void exit_section_(PsiBuilder builder,
                                     int level,
                                     PsiBuilder.Marker marker,
                                     @Nullable IElementType elementType,
                                     boolean result,
                                     boolean pinned,
                                     @Nullable FlexibleSearchParserUtils.Parser eatMore) {
        FlexibleSearchParserUtils.ErrorState state = FlexibleSearchParserUtils.ErrorState.get(builder);

        FlexibleSearchParserUtils.Frame frame = state.currentFrame;
        state.currentFrame = frame == null ? null : frame.parentFrame;
        if (frame != null && frame.elementType != null) elementType = frame.elementType;
        if (frame == null || level != frame.level) {
            LOG.error("Unbalanced error section: got " + frame + ", expected level " + level);
            if (frame != null) state.FRAMES.recycle(frame);
            close_marker_impl_(frame, marker, elementType, result);
            return;
        }

        if (((frame.modifiers & _AND_) | (frame.modifiers & _NOT_)) != 0) {
            close_marker_impl_(frame, marker, null, false);
            replace_variants_with_name_(state, frame, builder, result, pinned);
            state.predicateCount--;
            if ((frame.modifiers & _NOT_) != 0) state.predicateSign = !state.predicateSign;
        }
        else {
            close_frame_impl_(state, frame, builder, marker, elementType, result, pinned);
            exit_section_impl_(state, frame, builder, elementType, result, pinned, eatMore);
        }
        run_hooks_impl_(builder, state, pinned || result ? elementType : null);
        state.FRAMES.recycle(frame);
        state.level--;
    }

    public static <T> void register_hook_(PsiBuilder builder, FlexibleSearchParserUtils.Hook<T> hook, T param) {
        FlexibleSearchParserUtils.ErrorState state = FlexibleSearchParserUtils.ErrorState.get(builder);
        state.hooks = FlexibleSearchParserUtils.Hooks.concat(hook, param, state.level, state.hooks);
    }

    @SafeVarargs
    public static <T> void register_hook_(PsiBuilder builder, FlexibleSearchParserUtils.Hook<T[]> hook, T... param) {
        FlexibleSearchParserUtils.ErrorState state = FlexibleSearchParserUtils.ErrorState.get(builder);
        state.hooks = FlexibleSearchParserUtils.Hooks.concat(hook, param, state.level, state.hooks);
    }

    private static void run_hooks_impl_(PsiBuilder builder, FlexibleSearchParserUtils.ErrorState state, @Nullable IElementType elementType) {
        if (state.hooks == null) return;
        PsiBuilder.Marker marker = elementType == null ? null : (PsiBuilder.Marker)builder.getLatestDoneMarker();
        if (elementType != null && marker == null) {
            builder.mark().error("No expected done marker at offset " + builder.getCurrentOffset());
        }
        while (state.hooks != null && state.hooks.level >= state.level) {
            if (state.hooks.level == state.level) {
                marker = ((FlexibleSearchParserUtils.Hook<Object>)state.hooks.hook).run(builder, marker, state.hooks.param);
            }
            state.hooks = state.hooks.next;
        }
    }

    private static void exit_section_impl_(FlexibleSearchParserUtils.ErrorState state,
                                           FlexibleSearchParserUtils.Frame frame,
                                           PsiBuilder builder,
                                           @Nullable IElementType elementType,
                                           boolean result,
                                           boolean pinned,
                                           @Nullable FlexibleSearchParserUtils.Parser eatMore) {
        int initialPos = builder.rawTokenIndex();
        boolean willFail = !result && !pinned;
        replace_variants_with_name_(state, frame, builder, result, pinned);
        int lastErrorPos = getLastVariantPos(state, initialPos);
        if (!state.suppressErrors && eatMore != null) {
            state.suppressErrors = true;
            final boolean eatMoreFlagOnce = !builder.eof() && eatMore.parse(builder, frame.level + 1);
            boolean eatMoreFlag = eatMoreFlagOnce || !result && frame.position == initialPos && lastErrorPos > frame.position;

            PsiBuilderImpl.ProductionMarker latestDoneMarker =
                (pinned || result) && (state.altMode || elementType != null) &&
                eatMoreFlagOnce ? (PsiBuilderImpl.ProductionMarker)builder.getLatestDoneMarker() : null;
            PsiBuilder.Marker extensionMarker = null;
            IElementType extensionTokenType = null;
            // whitespace prefix makes the very first frame offset bigger than marker start offset which is always 0
            if (latestDoneMarker != null &&
                frame.position >= latestDoneMarker.getStartIndex() &&
                frame.position <= latestDoneMarker.getEndIndex()) {
                extensionMarker = ((PsiBuilder.Marker)latestDoneMarker).precede();
                extensionTokenType = latestDoneMarker.getTokenType();
                ((PsiBuilder.Marker)latestDoneMarker).drop();
            }
            // advance to the last error pos
            // skip tokens until lastErrorPos. parseAsTree might look better here...
            int parenCount = 0;
            while ((eatMoreFlag || parenCount > 0) && builder.rawTokenIndex() < lastErrorPos) {
                IElementType tokenType = builder.getTokenType();
                if (state.braces != null) {
                    if (tokenType == state.braces[0].getLeftBraceType()) parenCount ++;
                    else if (tokenType == state.braces[0].getRightBraceType()) parenCount --;
                }
                if (!(builder.rawTokenIndex() < lastErrorPos)) break;
                builder.advanceLexer();
                eatMoreFlag = eatMore.parse(builder, frame.level + 1);
            }
            boolean errorReported = frame.errorReportedAt == initialPos || !result && frame.errorReportedAt >= frame.position;
            if (errorReported) {
                if (eatMoreFlag) {
                    builder.advanceLexer();
                    parseAsTree(state, builder, frame.level + 1, DUMMY_BLOCK, true, TOKEN_ADVANCER, eatMore);
                }
            }
            else if (eatMoreFlag) {
                errorReported = reportError(builder, state, frame, null, true, true);
                parseAsTree(state, builder, frame.level + 1, DUMMY_BLOCK, true, TOKEN_ADVANCER, eatMore);
            }
            else if (eatMoreFlagOnce || (!result && frame.position != builder.rawTokenIndex()) || frame.errorReportedAt > initialPos) {
                errorReported = reportError(builder, state, frame, null, true, false);
            }
            else if (!result && pinned && frame.errorReportedAt < 0) {
                errorReported = reportError(builder, state, frame, elementType, false, false);
            }
            if (extensionMarker != null) {
                extensionMarker.done(extensionTokenType);
            }
            state.suppressErrors = false;
            if (errorReported || result) {
                state.clearVariants(true, 0);
                state.clearVariants(false, 0);
                state.lastExpectedVariantPos = -1;
            }
        }
        else if (!result && pinned && frame.errorReportedAt < 0) {
            // do not report if there are errors beyond current position
            if (lastErrorPos == initialPos) {
                // do not force, inner recoverRoot might have skipped some tokens
                reportError(builder, state, frame, elementType, false, false);
            }
            else if (lastErrorPos > initialPos) {
                // set error pos here as if it is reported for future reference
                frame.errorReportedAt = lastErrorPos;
            }
        }
        // propagate errorReportedAt up the stack to avoid duplicate reporting
        FlexibleSearchParserUtils.Frame prevFrame = willFail && eatMore == null ? null : state.currentFrame;
        if (prevFrame != null && prevFrame.errorReportedAt < frame.errorReportedAt) {
            prevFrame.errorReportedAt = frame.errorReportedAt;
        }
    }

    private static void close_frame_impl_(FlexibleSearchParserUtils.ErrorState state,
                                          FlexibleSearchParserUtils.Frame frame,
                                          PsiBuilder builder,
                                          PsiBuilder.Marker marker,
                                          IElementType elementType,
                                          boolean result,
                                          boolean pinned) {
        if (elementType != null && marker != null) {
            if (result || pinned) {
                if ((frame.modifiers & _COLLAPSE_) != 0) {
                    PsiBuilderImpl.ProductionMarker last = (PsiBuilderImpl.ProductionMarker)builder.getLatestDoneMarker();
                    if (last != null &&
                        last.getStartIndex() == frame.position &&
                        state.typeExtends(last.getTokenType(), elementType) &&
                        wasAutoSkipped(builder, builder.rawTokenIndex() - last.getEndIndex())) {
                        elementType = last.getTokenType();
                        ((PsiBuilder.Marker)last).drop();
                    }
                }
                if ((frame.modifiers & _UPPER_) != 0) {
                    marker.drop();
                    for (FlexibleSearchParserUtils.Frame f = frame.parentFrame; f != null; f = f.parentFrame) {
                        if (f.elementType == null) continue;
                        f.elementType = elementType;
                        break;
                    }
                }
                else if ((frame.modifiers & _LEFT_INNER_) != 0 && frame.leftMarker != null) {
                    marker.done(elementType);
                    frame.leftMarker.precede().done(((LighterASTNode)frame.leftMarker).getTokenType());
                    frame.leftMarker.drop();
                }
                else if ((frame.modifiers & _LEFT_) != 0 && frame.leftMarker != null) {
                    marker.drop();
                    frame.leftMarker.precede().done(elementType);
                }
                else {
                    if (frame.level == 0) builder.eof(); // skip whitespaces
                    marker.done(elementType);
                }
            }
            else {
                close_marker_impl_(frame, marker, null, false);
            }
        }
        else if (result || pinned) {
            if (marker != null) marker.drop();
            if ((frame.modifiers & _LEFT_INNER_) != 0 && frame.leftMarker != null) {
                frame.leftMarker.precede().done(((LighterASTNode)frame.leftMarker).getTokenType());
                frame.leftMarker.drop();
            }
        }
        else {
            close_marker_impl_(frame, marker, null, false);
        }
    }

    private static void close_marker_impl_(FlexibleSearchParserUtils.Frame frame, PsiBuilder.Marker marker, IElementType elementType, boolean result) {
        if (marker == null) return;
        if (result) {
            if (elementType != null) {
                marker.done(elementType);
            }
            else {
                marker.drop();
            }
        }
        else {
            if (frame != null) {
                int position = ((PsiBuilderImpl.ProductionMarker)marker).getStartIndex();
                if (frame.errorReportedAt > position && frame.parentFrame != null) {
                    frame.errorReportedAt = frame.parentFrame.errorReportedAt;
                }
            }
            marker.rollbackTo();
        }
    }

    private static void replace_variants_with_name_(FlexibleSearchParserUtils.ErrorState state,
                                                    FlexibleSearchParserUtils.Frame frame,
                                                    PsiBuilder builder,
                                                    boolean result,
                                                    boolean pinned) {
        int initialPos = builder.rawTokenIndex();
        boolean willFail = !result && !pinned;
        if (willFail && initialPos == frame.position && state.lastExpectedVariantPos == frame.position &&
            frame.name != null && state.variants.size() - frame.variantCount > 1) {
            state.clearVariants(true, frame.variantCount);
            addVariantInner(state, initialPos, frame.name);
        }
    }

    public static boolean report_error_(PsiBuilder builder, boolean result) {
        if (!result) report_error_(builder, FlexibleSearchParserUtils.ErrorState.get(builder), false);
        return result;
    }

    public static void report_error_(PsiBuilder builder, FlexibleSearchParserUtils.ErrorState state, boolean advance) {
        FlexibleSearchParserUtils.Frame frame = state.currentFrame;
        if (frame == null) {
            LOG.error("unbalanced enter/exit section call: got null");
            return;
        }
        int position = builder.rawTokenIndex();
        if (frame.errorReportedAt < position && getLastVariantPos(state, position + 1) <= position) {
            reportError(builder, state, frame, null, true, advance);
        }
    }

    public static boolean withProtectedLastVariantPos(PsiBuilder builder, int level, FlexibleSearchParserUtils.Parser parser) {
        FlexibleSearchParserUtils.ErrorState state = FlexibleSearchParserUtils.ErrorState.get(builder);
        int backup = state.lastExpectedVariantPos;
        boolean result = parser.parse(builder, level);
        state.lastExpectedVariantPos = backup;
        return result;
    }

    private static int getLastVariantPos(FlexibleSearchParserUtils.ErrorState state, int defValue) {
        return state.lastExpectedVariantPos < 0? defValue : state.lastExpectedVariantPos;
    }

    private static boolean reportError(PsiBuilder builder,
                                       FlexibleSearchParserUtils.ErrorState state,
                                       FlexibleSearchParserUtils.Frame frame,
                                       IElementType elementType,
                                       boolean force,
                                       boolean advance) {
        String expectedText = state.getExpectedText(builder);
        boolean notEmpty = isNotEmpty(expectedText);
        if (!(force || notEmpty || advance)) return false;

        String actual = "'" + first(notNullize(builder.getTokenText(), "null"), MAX_ERROR_TOKEN_TEXT, true) + "'";
        String message = expectedText + (builder.eof() ? "unexpected end of file" : notEmpty ? "got " + actual : actual + " unexpected");
        if (advance) {
//            PsiBuilder.Marker mark = builder.mark();
            builder.advanceLexer();
//            mark.error(message);
        }
        else if (!force) {
            PsiBuilder.Marker extensionMarker = null;
            IElementType extensionTokenType = null;
            PsiBuilderImpl.ProductionMarker latestDoneMarker = elementType == null ? null : (PsiBuilderImpl.ProductionMarker)builder.getLatestDoneMarker();
            if (latestDoneMarker != null &&
                frame.position >= latestDoneMarker.getStartIndex() &&
                frame.position <= latestDoneMarker.getEndIndex()) {
                extensionMarker = ((PsiBuilder.Marker)latestDoneMarker).precede();
                extensionTokenType = latestDoneMarker.getTokenType();
                ((PsiBuilder.Marker)latestDoneMarker).drop();
            }
//            builder.error(message);
            if (extensionMarker != null) extensionMarker.done(extensionTokenType);
        }
        else {
            //builder.error(message);
        }
        builder.eof(); // skip whitespaces
        frame.errorReportedAt = builder.rawTokenIndex();
        return true;
    }


    public static final Key<FlexibleSearchParserUtils.CompletionState> COMPLETION_STATE_KEY = Key.create("COMPLETION_STATE_KEY");

    public static class CompletionState implements Function<Object, String> {
        public final int offset;
        public final Collection<String> items = new THashSet<>();

        public CompletionState(int offset_) {
            offset = offset_;
        }

        @Nullable
        public String convertItem(Object o) {
            return o instanceof Object[] ? join((Object[]) o, this, " ") : o.toString();
        }

        @Override
        public String fun(Object o) {
            return convertItem(o);
        }

        public void addItem(@NotNull PsiBuilder builder, @NotNull String text) {
            items.add(text);
        }

        public boolean prefixMatches(@NotNull PsiBuilder builder, @NotNull String text) {
            int builderOffset = builder.getCurrentOffset();
            int diff = offset - builderOffset;
            int length = text.length();
            if (diff == 0) {
                return true;
            }
            else if (diff > 0 && diff <= length) {
                CharSequence fragment = builder.getOriginalText().subSequence(builderOffset, offset);
                return prefixMatches(fragment.toString(), text);
            }
            else if (diff < 0) {
                for (int i=-1; ; i--) {
                    IElementType type = builder.rawLookup(i);
                    int tokenStart = builder.rawTokenTypeStart(i);
                    if (isWhitespaceOrComment(builder, type)) {
                        diff = offset - tokenStart;
                    }
                    else if (type != null && tokenStart < offset) {
                        CharSequence fragment = builder.getOriginalText().subSequence(tokenStart, offset);
                        if (prefixMatches(fragment.toString(), text)) {
                            diff = offset - tokenStart;
                        }
                        break;
                    }
                    else break;
                }
                return diff >= 0 && diff < length;
            }
            return false;
        }

        public boolean prefixMatches(@NotNull String prefix, @NotNull String variant) {
            boolean matches = new CamelHumpMatcher(prefix, false).prefixMatches(variant.replace(' ', '_'));
            if (matches && isWhiteSpace(prefix.charAt(prefix.length() - 1))) {
                return startsWithIgnoreCase(variant, prefix);
            }
            return matches;
        }
    }

    public static class Builder extends PsiBuilderAdapter {
        public final FlexibleSearchParserUtils.ErrorState state;
        public final PsiParser parser;

        public Builder(PsiBuilder builder, FlexibleSearchParserUtils.ErrorState state_, PsiParser parser_) {
            super(builder);
            state = state_;
            parser = parser_;
        }

        public Lexer getLexer() {
            return ((PsiBuilderImpl)myDelegate).getLexer();
        }
    }

    public static PsiBuilder adapt_builder_(IElementType root, PsiBuilder builder, PsiParser parser) {
        return adapt_builder_(root, builder, parser, null);
    }

    public static PsiBuilder adapt_builder_(IElementType root, PsiBuilder builder, PsiParser parser, TokenSet[] extendsSets) {
        FlexibleSearchParserUtils.ErrorState state = new FlexibleSearchParserUtils.ErrorState();
        FlexibleSearchParserUtils.ErrorState.initState(state, builder, root, extendsSets);
        return new FlexibleSearchParserUtils.Builder(builder, state, parser);
    }

    public static class ErrorState {
        TokenSet[] extendsSets;
        public PairProcessor<IElementType, IElementType> altExtendsChecker;

        int predicateCount;
        int level;
        boolean predicateSign = true;
        boolean suppressErrors;
        FlexibleSearchParserUtils.Hooks<?> hooks;
        public FlexibleSearchParserUtils.Frame currentFrame;
        public FlexibleSearchParserUtils.CompletionState completionState;

        private boolean caseSensitive;
        public BracePair[] braces;
        public boolean altMode;

        int lastExpectedVariantPos = -1;
        FlexibleSearchParserUtils.MyList<FlexibleSearchParserUtils.Variant> variants = new FlexibleSearchParserUtils.MyList<>(INITIAL_VARIANTS_SIZE);
        FlexibleSearchParserUtils.MyList<FlexibleSearchParserUtils.Variant> unexpected = new FlexibleSearchParserUtils.MyList<>(INITIAL_VARIANTS_SIZE / 10);

        final LimitedPool<FlexibleSearchParserUtils.Variant> VARIANTS = new LimitedPool<>(VARIANTS_POOL_SIZE, new LimitedPool.ObjectFactory<FlexibleSearchParserUtils.Variant>() {
            @NotNull
            @Override
            public FlexibleSearchParserUtils.Variant create() {
                return new FlexibleSearchParserUtils.Variant();
            }

            @Override
            public void cleanup(@NotNull FlexibleSearchParserUtils.Variant o) {
            }
        });
        final LimitedPool<FlexibleSearchParserUtils.Frame> FRAMES = new LimitedPool<>(FRAMES_POOL_SIZE, new LimitedPool.ObjectFactory<FlexibleSearchParserUtils.Frame>() {
            @NotNull
            @Override
            public FlexibleSearchParserUtils.Frame create() {
                return new FlexibleSearchParserUtils.Frame();
            }

            @Override
            public void cleanup(@NotNull FlexibleSearchParserUtils.Frame o) {
            }
        });

        public static FlexibleSearchParserUtils.ErrorState get(PsiBuilder builder) {
            return ((FlexibleSearchParserUtils.Builder)builder).state;
        }

        public static void initState(FlexibleSearchParserUtils.ErrorState state, PsiBuilder builder, IElementType root, TokenSet[] extendsSets) {
            state.extendsSets = extendsSets;
            PsiFile file = builder.getUserDataUnprotected(FileContextUtil.CONTAINING_FILE_KEY);
            state.completionState = file == null? null: file.getUserData(COMPLETION_STATE_KEY);
            Language language = file == null? root.getLanguage() : file.getLanguage();
            state.caseSensitive = language.isCaseSensitive();
            PairedBraceMatcher matcher = LanguageBraceMatching.INSTANCE.forLanguage(language);
            state.braces = matcher == null ? null : matcher.getPairs();
            if (state.braces != null && state.braces.length == 0) state.braces = null;
        }

        public String getExpectedText(PsiBuilder builder) {
            int position = builder.rawTokenIndex();
            StringBuilder sb = new StringBuilder();
            if (addExpected(sb, position, true)) {
                sb.append(" expected, ");
            }
            return sb.toString();
        }

        private boolean addExpected(StringBuilder sb, int position, boolean expected) {
            FlexibleSearchParserUtils.MyList<FlexibleSearchParserUtils.Variant> list = expected ? variants : unexpected;
            String[] strings = new String[list.size()];
            long[] hashes = new long[strings.length];
            Arrays.fill(strings, "");
            int count = 0;
            loop: for (FlexibleSearchParserUtils.Variant variant : list) {
                if (position == variant.position) {
                    String text = variant.object.toString();
                    long hash = StringHash.calc(text);
                    for (int i=0; i<count; i++) {
                        if (hashes[i] == hash) continue loop;
                    }
                    hashes[count] = hash;
                    strings[count] = text;
                    count++;
                }
            }
            Arrays.sort(strings);
            count = 0;
            for (String s : strings) {
                if (s.length() == 0) continue;
                if (count++ > 0) {
                    if (count > MAX_VARIANTS_TO_DISPLAY) {
                        sb.append(" and ...");
                        break;
                    }
                    else {
                        sb.append(", ");
                    }
                }
                char c = s.charAt(0);
                String displayText = c == '<' || isJavaIdentifierStart(c) ? s : '\'' + s + '\'';
                sb.append(displayText);
            }
            if (count > 1 && count < MAX_VARIANTS_TO_DISPLAY) {
                int idx = sb.lastIndexOf(", ");
                sb.replace(idx, idx + 1, " or");
            }
            return count > 0;
        }

        public void clearVariants(FlexibleSearchParserUtils.Frame frame) {
            clearVariants(true, frame == null ? 0 : frame.variantCount);
        }

        void clearVariants(boolean expected, int start) {
            FlexibleSearchParserUtils.MyList<FlexibleSearchParserUtils.Variant> list = expected? variants : unexpected;
            if (start < 0 || start >= list.size()) return;
            for (int i = start, len = list.size(); i < len; i ++) {
                VARIANTS.recycle(list.get(i));
            }
            list.setSize(start);
        }

        public boolean typeExtends(IElementType child, IElementType parent) {
            if (child == parent) return true;
            if (extendsSets != null) {
                for (TokenSet set : extendsSets) {
                    if (set.contains(child) && set.contains(parent)) return true;
                }
            }
            return altExtendsChecker != null && altExtendsChecker.process(child, parent);
        }
    }

    public static class Frame {
        public FlexibleSearchParserUtils.Frame parentFrame;
        public IElementType elementType;

        public int offset;
        public int position;
        public int level;
        public int modifiers;
        public String name;
        public int variantCount;
        public int errorReportedAt;
        public PsiBuilder.Marker leftMarker;

        public Frame() {
        }

        public FlexibleSearchParserUtils.Frame init(PsiBuilder builder,
                                                  FlexibleSearchParserUtils.ErrorState state,
                                                  int level_,
                                                  int modifiers_,
                                                  IElementType elementType_,
                                                  String name_) {
            parentFrame = state.currentFrame;
            elementType = elementType_;

            offset = builder.getCurrentOffset();
            position = builder.rawTokenIndex();
            level = level_;
            modifiers = modifiers_;
            name = name_;
            variantCount = state.variants.size();
            errorReportedAt = -1;

            leftMarker = null;
            return this;
        }

        @Override
        public String toString() {
            String mod = modifiers == _NONE_ ? "_NONE_, " :
                ((modifiers & _COLLAPSE_) != 0? "_CAN_COLLAPSE_, ": "") +
                ((modifiers & _LEFT_) != 0? "_LEFT_, ": "") +
                ((modifiers & _LEFT_INNER_) != 0? "_LEFT_INNER_, ": "") +
                ((modifiers & _AND_) != 0? "_AND_, ": "") +
                ((modifiers & _NOT_) != 0? "_NOT_, ": "") +
                ((modifiers & _UPPER_) != 0 ? "_UPPER_, " : "");
            return String.format("{%s:%s:%d, %d, %s%s, %s}", offset, position, level, errorReportedAt, mod, elementType, name);
        }
    }


    private static class Variant {
        int position;
        Object object;

        public FlexibleSearchParserUtils.Variant init(int pos, Object o) {
            position = pos;
            object = o;
            return this;
        }

        @Override
        public String toString() {
            return "<" + position + ", " + object + ">";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            FlexibleSearchParserUtils.Variant variant = (FlexibleSearchParserUtils.Variant)o;

            if (position != variant.position) return false;
            if (!this.object.equals(variant.object)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = position;
            result = 31 * result + object.hashCode();
            return result;
        }
    }

    private static class Hooks<T> {
        final FlexibleSearchParserUtils.Hook<T> hook;
        final T param;
        final int level;
        final FlexibleSearchParserUtils.Hooks<?> next;

        Hooks(FlexibleSearchParserUtils.Hook<T> hook, T param, int level, FlexibleSearchParserUtils.Hooks next) {
            this.hook = hook;
            this.param = param;
            this.level = level;
            this.next = next;
        }

        static <E> FlexibleSearchParserUtils.Hooks<E> concat(FlexibleSearchParserUtils.Hook<E> hook, E param, int level, FlexibleSearchParserUtils.Hooks<?> hooks) {
            return new FlexibleSearchParserUtils.Hooks<>(hook, param, level, hooks);
        }
    }


    private static final int MAX_CHILDREN_IN_TREE = 10;
    public static boolean parseAsTree(FlexibleSearchParserUtils.ErrorState state, final PsiBuilder builder, int level, final IElementType chunkType,
                                      boolean checkBraces, final FlexibleSearchParserUtils.Parser parser, final FlexibleSearchParserUtils.Parser eatMoreCondition) {
        final LinkedList<Pair<PsiBuilder.Marker, PsiBuilder.Marker>> parenList = new LinkedList<>();
        final LinkedList<Pair<PsiBuilder.Marker, Integer>> siblingList = new LinkedList<>();
        PsiBuilder.Marker marker = null;

        final Runnable checkSiblingsRunnable = () -> {
            main:
            while (!siblingList.isEmpty()) {
                final Pair<PsiBuilder.Marker, PsiBuilder.Marker> parenPair = parenList.peek();
                final int rating = siblingList.getFirst().second;
                int count = 0;
                for (Pair<PsiBuilder.Marker, Integer> pair : siblingList) {
                    if (pair.second != rating || parenPair != null && pair.first == parenPair.second) break main;
                    if (++count >= MAX_CHILDREN_IN_TREE) {
                        PsiBuilder.Marker parentMarker = pair.first.precede();
                        parentMarker.setCustomEdgeTokenBinders(WhitespacesBinders.GREEDY_LEFT_BINDER, null);
                        while (count-- > 0) {
                            siblingList.removeFirst();
                        }
                        parentMarker.done(chunkType);
                        siblingList.addFirst(Pair.create(parentMarker, rating + 1));
                        continue main;
                    }
                }
                break;
            }
        };
        boolean checkParens = state.braces != null && checkBraces;
        int totalCount = 0;
        int tokenCount = 0;
        if (checkParens) {
            int tokenIdx = -1;
            while (builder.rawLookup(tokenIdx) == TokenType.WHITE_SPACE) tokenIdx --;
            LighterASTNode doneMarker = builder.rawLookup(tokenIdx) == state.braces[0].getLeftBraceType() ? builder.getLatestDoneMarker() : null;
            if (doneMarker != null && doneMarker.getStartOffset() == builder.rawTokenTypeStart(tokenIdx) && doneMarker.getTokenType() == TokenType.ERROR_ELEMENT) {
                parenList.add(Pair.create(((PsiBuilder.Marker)doneMarker).precede(), null));
            }
        }
        int c = current_position_(builder);
        while (true) {
            final IElementType tokenType = builder.getTokenType();
            if (checkParens && (tokenType == state.braces[0].getLeftBraceType() || tokenType == state.braces[0].getRightBraceType() && !parenList.isEmpty())) {
                if (marker != null) {
                    marker.done(chunkType);
                    siblingList.addFirst(Pair.create(marker, 1));
                    marker = null;
                    tokenCount = 0;
                }
                if (tokenType == state.braces[0].getLeftBraceType()) {
                    final Pair<PsiBuilder.Marker, Integer> prev = siblingList.peek();
                    parenList.addFirst(Pair.create(builder.mark(), prev == null ? null : prev.first));
                }
                checkSiblingsRunnable.run();
                builder.advanceLexer();
                if (tokenType == state.braces[0].getRightBraceType()) {
                    final Pair<PsiBuilder.Marker, PsiBuilder.Marker> pair = parenList.removeFirst();
                    pair.first.done(chunkType);
                    // drop all markers inside parens
                    while (!siblingList.isEmpty() && siblingList.getFirst().first != pair.second) {
                        siblingList.removeFirst();
                    }
                    siblingList.addFirst(Pair.create(pair.first, 1));
                    checkSiblingsRunnable.run();
                }
            }
            else {
                if (marker == null) {
                    marker = builder.mark();
                    marker.setCustomEdgeTokenBinders(WhitespacesBinders.GREEDY_LEFT_BINDER, null);
                }
                boolean result = (!parenList.isEmpty() || eatMoreCondition.parse(builder, level + 1)) && parser.parse(builder, level + 1);
                if (result) {
                    tokenCount++;
                    totalCount++;
                }
                if (!result) {
                    break;
                }
            }

            if (tokenCount >= MAX_CHILDREN_IN_TREE) {
                marker.done(chunkType);
                siblingList.addFirst(Pair.create(marker, 1));
                checkSiblingsRunnable.run();
                marker = null;
                tokenCount = 0;
            }
            if (!empty_element_parsed_guard_(builder, "parseAsTree", c)) break;
            c = current_position_(builder);
        }
        if (marker != null) marker.drop();
        for (Pair<PsiBuilder.Marker, PsiBuilder.Marker> pair : parenList) {
            pair.first.drop();
        }
        return totalCount != 0;
    }

    private static class DummyBlockElementType extends IElementType implements ICompositeElementType {
        DummyBlockElementType() {
            super("DUMMY_BLOCK", Language.ANY);
        }

        @NotNull
        @Override
        public ASTNode createCompositeNode() {
            return new FlexibleSearchParserUtils.DummyBlock();
        }
    }

    public static class DummyBlock extends CompositePsiElement {
        DummyBlock() {
            super(DUMMY_BLOCK);
        }

        @NotNull
        @Override
        public PsiReference[] getReferences() {
            return PsiReference.EMPTY_ARRAY;
        }

        @NotNull
        @Override
        public Language getLanguage() {
            return getParent().getLanguage();
        }
    }

    private static class MyList<E> extends ArrayList<E> {
        MyList(int initialCapacity) {
            super(initialCapacity);
        }

        protected void setSize(int fromIndex) {
            removeRange(fromIndex, size());
        }

        @Override
        public boolean add(E e) {
            int size = size();
            if (size >= MAX_VARIANTS_SIZE) {
                removeRange(MAX_VARIANTS_SIZE / 4, size - MAX_VARIANTS_SIZE / 4);
            }
            return super.add(e);
        }
    }
}
