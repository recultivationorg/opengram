package org.telegram.messenger;

import android.content.Context;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Hides channel posts and bot-chat messages whose text contains a keyword.
 * The keyword list is a per-account snapshot. StrongBox mode authenticates the
 * header as additional data. A bad seal, a mode mismatch, or a truncated file
 * clears the in-memory list and stops filtering.
 */
public final class OpengramMessageFilter {

    public static final int MAX_KEYWORDS = 32;
    public static final int MAX_KEYWORD_LENGTH = 48;

    private static final int ACCOUNT_SLOTS = 4;
    private static final String FILE_PREFIX = "opengram-filter-";
    private static final String FILE_SUFFIX = ".bin";
    private static final Object lock = new Object();
    private static final State[] states = new State[ACCOUNT_SLOTS];

    public enum Phase {
        DISABLED,
        READY,
        ERROR
    }

    public static final class Snapshot {
        public final OpengramSnapshotFile.Status status;
        public final List<String> keywords;

        Snapshot(OpengramSnapshotFile.Status status, List<String> keywords) {
            this.status = status;
            this.keywords = keywords == null ? Collections.<String>emptyList() : keywords;
        }
    }

    private static final class State {
        boolean loaded;
        boolean error;
        long clientUserId;
        int mode;
        final ArrayList<String> keywords = new ArrayList<>();
        final ArrayList<String> folded = new ArrayList<>();

        void clear() {
            loaded = false;
            error = false;
            clientUserId = 0;
            mode = 0;
            keywords.clear();
            folded.clear();
        }

        void fail(long clientUserId, int mode) {
            loaded = true;
            error = true;
            this.clientUserId = clientUserId;
            this.mode = mode;
            keywords.clear();
            folded.clear();
        }

        void replaceKeywords(Collection<String> next) {
            keywords.clear();
            folded.clear();
            if (next == null) {
                return;
            }
            for (String keyword : next) {
                if (keyword == null) {
                    continue;
                }
                keywords.add(keyword);
                if (!keyword.isEmpty()) {
                    folded.add(foldYo(keyword.toLowerCase(Locale.ROOT)));
                }
            }
        }
    }

    private OpengramMessageFilter() {
    }

    public static List<String> parseKeywords(String raw) {
        ArrayList<String> keywords = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return keywords;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int start = 0;
        for (int i = 0; i <= raw.length(); i++) {
            if (i != raw.length() && raw.charAt(i) != ',') {
                continue;
            }
            String token = raw.substring(start, i).replace('\n', ' ').replace('\r', ' ').trim();
            start = i + 1;
            if (token.isEmpty() || token.length() > MAX_KEYWORD_LENGTH) {
                continue;
            }
            if (!seen.add(token.toLowerCase(Locale.ROOT))) {
                continue;
            }
            keywords.add(token);
            if (keywords.size() == MAX_KEYWORDS) {
                break;
            }
        }
        return keywords;
    }

    private static final String[][] PLURAL_PAIRS = {
        {"child", "children"},
        {"person", "people"},
        {"man", "men"},
        {"woman", "women"},
        {"mouse", "mice"},
        {"goose", "geese"},
        {"tooth", "teeth"},
        {"foot", "feet"},
        {"ox", "oxen"},
        {"knife", "knives"},
        {"life", "lives"},
        {"wife", "wives"},
        {"wolf", "wolves"},
        {"leaf", "leaves"},
        {"half", "halves"},
        {"calf", "calves"},
        {"loaf", "loaves"},
        {"self", "selves"},
        {"shelf", "shelves"},
        {"thief", "thieves"},
        {"elf", "elves"},
        {"sheaf", "sheaves"},
        {"bus", "buses"}
    };

    private static final String[] UNCHANGED_WORDS = {
        "series", "species", "news", "this", "his", "its", "plus", "thus"
    };

    private static final String[][] RUSSIAN_PAIRS = {
        {"человек", "люди"},
        {"ребенок", "дети"},
        {"друг", "друзья"},
        {"брат", "братья"},
        {"стул", "стулья"},
        {"сын", "сыновья"},
        {"муж", "мужья"},
        {"дерево", "деревья"},
        {"перо", "перья"},
        {"лист", "листья"},
        {"глаз", "глаза"},
        {"дом", "дома"},
        {"город", "города"},
        {"номер", "номера"},
        {"учитель", "учителя"},
        {"доктор", "доктора"},
        {"профессор", "профессора"},
        {"берег", "берега"},
        {"лес", "леса"},
        {"снег", "снега"},
        {"поезд", "поезда"},
        {"адрес", "адреса"},
        {"директор", "директора"},
        {"паспорт", "паспорта"},
        {"цвет", "цвета"},
        {"голос", "голоса"},
        {"вечер", "вечера"},
        {"мастер", "мастера"},
        {"сосед", "соседи"},
        {"черт", "черти"},
        {"чудо", "чудеса"},
        {"небо", "небеса"},
        {"мать", "матери"},
        {"дочь", "дочери"},
        {"имя", "имена"},
        {"время", "времена"},
        {"племя", "племена"},
        {"семя", "семена"},
        {"знамя", "знамена"},
        {"стремя", "стремена"},
        {"пламя", "пламена"},
        {"вымя", "вымена"},
        {"темя", "темена"},
        {"бремя", "бремена"}
    };

    private static final String[] RUSSIAN_INDECLINABLE = {
        "кофе", "кафе", "метро", "пальто", "кино", "радио", "бюро", "депо", "какао", "такси", "меню", "шоссе", "ателье"
    };

    public static boolean matchesText(CharSequence text, Collection<String> keywords) {
        if (text == null || text.length() == 0 || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String haystack = foldYo(text.toString().toLowerCase(Locale.ROOT));
        for (String keyword : keywords) {
            if (keyword == null || keyword.isEmpty()) {
                continue;
            }
            if (keywordMatches(haystack, foldYo(keyword.toLowerCase(Locale.ROOT)))) {
                return true;
            }
        }
        return false;
    }

    private static String foldYo(String value) {
        if (value.indexOf('ё') < 0) {
            return value;
        }
        return value.replace('ё', 'е');
    }

    private static boolean keywordMatches(String haystackLower, String keywordLower) {
        if (!lettersAndSpaces(keywordLower)) {
            return haystackLower.contains(keywordLower);
        }
        ArrayList<String> words = letterWords(keywordLower);
        if (words.isEmpty()) {
            return haystackLower.contains(keywordLower);
        }
        return phraseMatches(haystackLower, words);
    }

    private static boolean lettersAndSpaces(String keyword) {
        boolean letter = false;
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                continue;
            }
            if (!Character.isLetter(c)) {
                return false;
            }
            letter = true;
        }
        return letter;
    }

    private static ArrayList<String> letterWords(String text) {
        ArrayList<String> words = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= text.length(); i++) {
            boolean letter = i < text.length() && Character.isLetter(text.charAt(i));
            if (letter && start < 0) {
                start = i;
            } else if (!letter && start >= 0) {
                words.add(text.substring(start, i));
                start = -1;
            }
        }
        return words;
    }

    private static boolean phraseMatches(String haystack, ArrayList<String> words) {
        for (int i = 0; i < haystack.length(); i++) {
            if (!Character.isLetter(haystack.charAt(i))) {
                continue;
            }
            if (i > 0 && Character.isLetter(haystack.charAt(i - 1))) {
                continue;
            }
            if (phraseAt(haystack, i, words)) {
                return true;
            }
        }
        return false;
    }

    private static boolean phraseAt(String haystack, int start, ArrayList<String> words) {
        int pos = start;
        for (int w = 0; w < words.size(); w++) {
            if (w > 0) {
                if (pos >= haystack.length() || !Character.isWhitespace(haystack.charAt(pos))) {
                    return false;
                }
                while (pos < haystack.length() && Character.isWhitespace(haystack.charAt(pos))) {
                    pos++;
                }
            }
            int end = pos;
            while (end < haystack.length() && Character.isLetter(haystack.charAt(end))) {
                end++;
            }
            if (end == pos) {
                return false;
            }
            String found = haystack.substring(pos, end);
            if (w == words.size() - 1) {
                if (!sameWord(words.get(w), found)) {
                    return false;
                }
            } else if (!found.equals(words.get(w))) {
                return false;
            }
            pos = end;
        }
        return true;
    }

    private static boolean sameWord(String keywordWord, String found) {
        if (keywordWord.equals(found)) {
            return true;
        }
        if (isCyrillicWord(keywordWord)) {
            return russianSame(keywordWord, found);
        }
        if (!isLatinWord(keywordWord)) {
            return false;
        }
        String pair = pairOf(keywordWord);
        if (pair != null) {
            return pair.equals(found);
        }
        if (keywordWord.length() < 3 || isUnchanged(keywordWord)) {
            return false;
        }
        String plural = pluralize(keywordWord);
        if (plural != null && !isUnchanged(plural) && plural.equals(found)) {
            return true;
        }
        String singular = singularize(keywordWord);
        return singular != null && singular.equals(found);
    }

    private static boolean isLatinWord(String word) {
        if (word.isEmpty()) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }

    private static boolean isCyrillicWord(String word) {
        if (word.isEmpty()) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c < 'а' || c > 'я') {
                return false;
            }
        }
        return true;
    }

    private static boolean russianSame(String keyword, String found) {
        if (!isCyrillicWord(found)) {
            return false;
        }
        if (russianPaired(keyword, found)) {
            return true;
        }
        if (keyword.length() < 2 || found.length() < 2) {
            return false;
        }
        String plural = russianPlural(keyword);
        if (plural != null && plural.equals(found)) {
            return true;
        }
        String other = russianPlural(found);
        return other != null && other.equals(keyword);
    }

    private static boolean russianPaired(String a, String b) {
        for (int i = 0; i < RUSSIAN_PAIRS.length; i++) {
            String left = RUSSIAN_PAIRS[i][0];
            String right = RUSSIAN_PAIRS[i][1];
            if ((a.equals(left) && b.equals(right)) || (a.equals(right) && b.equals(left))) {
                return true;
            }
        }
        return false;
    }

    private static boolean russianReplacesRegular(String word) {
        if (isRussianIndeclinable(word)) {
            return true;
        }
        for (int i = 0; i < RUSSIAN_PAIRS.length; i++) {
            if (word.equals(RUSSIAN_PAIRS[i][1])) {
                return true;
            }
            if (word.equals(RUSSIAN_PAIRS[i][0]) && !"лист".equals(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRussianIndeclinable(String word) {
        for (int i = 0; i < RUSSIAN_INDECLINABLE.length; i++) {
            if (word.equals(RUSSIAN_INDECLINABLE[i])) {
                return true;
            }
        }
        return false;
    }

    private static String russianPlural(String word) {
        if (word.length() < 2 || russianReplacesRegular(word)) {
            return null;
        }
        char last = word.charAt(word.length() - 1);
        if (last == 'ы' || last == 'и') {
            return null;
        }
        char prev = word.charAt(word.length() - 2);
        String stem = word.substring(0, word.length() - 1);
        if (last == 'а') {
            return stem + (hushingOrVelar(prev) ? "и" : "ы");
        }
        if (last == 'я') {
            return stem + "и";
        }
        if (last == 'о') {
            return stem + "а";
        }
        if (last == 'е') {
            return stem + (prev == 'ц' || hushingOrVelar(prev) ? "а" : "я");
        }
        if (last == 'ь' || last == 'й') {
            return stem + "и";
        }
        if (isRussianConsonant(last)) {
            return word + (hushingOrVelar(last) ? "и" : "ы");
        }
        return null;
    }

    private static boolean hushingOrVelar(char c) {
        return c == 'г' || c == 'к' || c == 'х' || c == 'ж' || c == 'ч' || c == 'ш' || c == 'щ';
    }

    private static boolean isRussianConsonant(char c) {
        return c == 'б' || c == 'в' || c == 'г' || c == 'д' || c == 'ж' || c == 'з'
            || c == 'к' || c == 'л' || c == 'м' || c == 'н' || c == 'п' || c == 'р'
            || c == 'с' || c == 'т' || c == 'ф' || c == 'х' || c == 'ц' || c == 'ч'
            || c == 'ш' || c == 'щ';
    }

    private static String pairOf(String word) {
        for (int i = 0; i < PLURAL_PAIRS.length; i++) {
            if (word.equals(PLURAL_PAIRS[i][0])) {
                return PLURAL_PAIRS[i][1];
            }
            if (word.equals(PLURAL_PAIRS[i][1])) {
                return PLURAL_PAIRS[i][0];
            }
        }
        return null;
    }

    private static boolean isUnchanged(String word) {
        for (int i = 0; i < UNCHANGED_WORDS.length; i++) {
            if (word.equals(UNCHANGED_WORDS[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean consonantY(String word) {
        if (word.length() < 2 || word.charAt(word.length() - 1) != 'y') {
            return false;
        }
        char prev = word.charAt(word.length() - 2);
        return prev != 'a' && prev != 'e' && prev != 'i' && prev != 'o' && prev != 'u';
    }

    private static String pluralize(String word) {
        if (consonantY(word)) {
            return word.substring(0, word.length() - 1) + "ies";
        }
        if (word.endsWith("ch") || word.endsWith("sh") || word.endsWith("x") || word.endsWith("z") || word.endsWith("ss")) {
            if (word.endsWith("es")) {
                return null;
            }
            return word + "es";
        }
        if (word.endsWith("s") && !word.endsWith("ss")) {
            return word + "es";
        }
        if (word.endsWith("s")) {
            return null;
        }
        return word + "s";
    }

    private static String singularize(String word) {
        if (word.endsWith("ies") && word.length() > 4) {
            char prev = word.charAt(word.length() - 4);
            if (prev != 'a' && prev != 'e' && prev != 'i' && prev != 'o' && prev != 'u') {
                return word.substring(0, word.length() - 3) + "y";
            }
        }
        if (word.endsWith("es") && word.length() > 3) {
            String stem = word.substring(0, word.length() - 2);
            if (stem.endsWith("ss") || stem.endsWith("sh") || stem.endsWith("ch") || stem.endsWith("x") || stem.endsWith("z") || endsWithSingleS(stem)) {
                return stem;
            }
        }
        if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 3) {
            return word.substring(0, word.length() - 1);
        }
        return null;
    }

    private static boolean endsWithSingleS(String word) {
        if (word.length() < 2 || !word.endsWith("s") || word.endsWith("ss")) {
            return false;
        }
        char prev = word.charAt(word.length() - 2);
        return prev == 'a' || prev == 'e' || prev == 'i' || prev == 'o' || prev == 'u';
    }

    public static boolean messageMatches(MessageObject message, Collection<String> keywords) {
        if (message == null || keywords == null || keywords.isEmpty()) {
            return false;
        }
        ArrayList<String> folded = new ArrayList<>(keywords.size());
        for (String keyword : keywords) {
            if (keyword == null || keyword.isEmpty()) {
                continue;
            }
            folded.add(foldYo(keyword.toLowerCase(Locale.ROOT)));
        }
        return messageMatchesPrepared(message, folded);
    }

    private static boolean messageMatchesPrepared(MessageObject message, List<String> keywords) {
        if (message == null || keywords == null || keywords.isEmpty()) {
            return false;
        }
        TLRPC.Message owner = message.messageOwner;
        if (owner == null) {
            return matchesPrepared(message.messageText, keywords);
        }
        boolean labeledMedia = owner.media != null && !(owner.media instanceof TLRPC.TL_messageMediaEmpty);
        if (!labeledMedia && matchesPrepared(message.messageText, keywords)) {
            return true;
        }
        if (matchesPrepared(owner.message, keywords)) {
            return true;
        }
        if (owner.media instanceof TLRPC.TL_messageMediaPoll) {
            TLRPC.TL_messageMediaPoll pollMedia = (TLRPC.TL_messageMediaPoll) owner.media;
            if (pollMedia.poll != null) {
                if (pollMedia.poll.question != null && matchesPrepared(pollMedia.poll.question.text, keywords)) {
                    return true;
                }
                ArrayList<TLRPC.PollAnswer> answers = pollMedia.poll.answers;
                if (answers != null) {
                    for (int i = 0; i < answers.size(); i++) {
                        TLRPC.PollAnswer answer = answers.get(i);
                        if (answer != null && answer.text != null && matchesPrepared(answer.text.text, keywords)) {
                            return true;
                        }
                    }
                }
            }
            if (pollMedia.results != null && matchesPrepared(pollMedia.results.solution, keywords)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPrepared(CharSequence text, List<String> foldedKeywords) {
        if (text == null || text.length() == 0 || foldedKeywords == null || foldedKeywords.isEmpty()) {
            return false;
        }
        String haystack = foldYo(text.toString().toLowerCase(Locale.ROOT));
        for (int i = 0; i < foldedKeywords.size(); i++) {
            String keyword = foldedKeywords.get(i);
            if (keyword != null && keywordMatches(haystack, keyword)) {
                return true;
            }
        }
        return false;
    }

    public static boolean appliesToDialog(boolean encrypted, boolean broadcastChannel, boolean bot) {
        if (encrypted) {
            return false;
        }
        return broadcastChannel || bot;
    }

    public static boolean writeKeywords(File file, int mode, long clientUserId, Collection<String> keywords, OpengramSnapshotFile.Sealer sealer) {
        LinkedHashSet<String> peers = new LinkedHashSet<>();
        if (keywords != null) {
            for (String keyword : keywords) {
                if (keyword == null) {
                    continue;
                }
                String token = keyword.replace('\n', ' ').replace('\r', ' ').trim();
                if (token.isEmpty() || token.length() > MAX_KEYWORD_LENGTH || token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0) {
                    continue;
                }
                if (!peers.add(token)) {
                    continue;
                }
                if (peers.size() == MAX_KEYWORDS) {
                    break;
                }
            }
        }
        return OpengramSnapshotFile.write(file, new OpengramSnapshotFile.Record(mode, 0L, new long[]{clientUserId}, peers), sealer);
    }

    public static Snapshot readKeywords(File file, int mode, long clientUserId, OpengramSnapshotFile.Sealer sealer) {
        OpengramSnapshotFile.ReadResult read = OpengramSnapshotFile.read(file, mode, sealer);
        if (read.status != OpengramSnapshotFile.Status.OK || read.record == null) {
            return new Snapshot(read.status, Collections.<String>emptyList());
        }
        if (read.record.accountIds.length != 1 || read.record.accountIds[0] != clientUserId) {
            return new Snapshot(OpengramSnapshotFile.Status.CORRUPT, Collections.<String>emptyList());
        }
        ArrayList<String> keywords = new ArrayList<>();
        for (String peer : read.record.peers) {
            if (peer == null || peer.indexOf('\n') >= 0 || peer.indexOf('\r') >= 0) {
                return new Snapshot(OpengramSnapshotFile.Status.CORRUPT, Collections.<String>emptyList());
            }
            String token = peer.trim();
            if (token.isEmpty() || token.length() > MAX_KEYWORD_LENGTH) {
                return new Snapshot(OpengramSnapshotFile.Status.CORRUPT, Collections.<String>emptyList());
            }
            keywords.add(token);
            if (keywords.size() > MAX_KEYWORDS) {
                return new Snapshot(OpengramSnapshotFile.Status.CORRUPT, Collections.<String>emptyList());
            }
        }
        return new Snapshot(OpengramSnapshotFile.Status.OK, keywords);
    }

    public static Phase phase(int account) {
        if (!validAccount(account) || !UserConfig.getInstance(account).messageFilter) {
            return Phase.DISABLED;
        }
        synchronized (lock) {
            ensureLoadedLocked(account);
            return stateLocked(account).error ? Phase.ERROR : Phase.READY;
        }
    }

    public static int keywordCount(int account) {
        if (phase(account) != Phase.READY) {
            return 0;
        }
        synchronized (lock) {
            return stateLocked(account).keywords.size();
        }
    }

    public static String keywordsText(int account) {
        if (phase(account) != Phase.READY) {
            return "";
        }
        synchronized (lock) {
            ArrayList<String> keywords = stateLocked(account).keywords;
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < keywords.size(); i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(keywords.get(i));
            }
            return builder.toString();
        }
    }

    public static boolean enable(int account, int mode) {
        if (!validAccount(account)) {
            return false;
        }
        if (mode != OpengramSnapshotFile.MODE_STRONGBOX && mode != OpengramSnapshotFile.MODE_PLAINTEXT) {
            return false;
        }
        UserConfig config = UserConfig.getInstance(account);
        long clientUserId = config.getClientUserId();
        if (clientUserId <= 0) {
            return false;
        }
        if (mode == OpengramSnapshotFile.MODE_STRONGBOX && OpengramStrongBox.forFilter(clientUserId).probeKey() != OpengramStrongBox.Probe.AVAILABLE) {
            return false;
        }
        OpengramSnapshotFile.Sealer sealer = mode == OpengramSnapshotFile.MODE_STRONGBOX ? OpengramStrongBox.forFilter(clientUserId) : null;
        if (!writeKeywords(file(clientUserId), mode, clientUserId, Collections.<String>emptyList(), sealer)) {
            return false;
        }
        config.messageFilter = true;
        config.messageFilterMode = mode;
        config.saveConfig(false);
        synchronized (lock) {
            State state = stateLocked(account);
            state.loaded = true;
            state.error = false;
            state.clientUserId = clientUserId;
            state.mode = mode;
            state.replaceKeywords(Collections.<String>emptyList());
        }
        notifyDialogs(account);
        return true;
    }

    public static void disable(int account) {
        if (!validAccount(account)) {
            return;
        }
        UserConfig config = UserConfig.getInstance(account);
        long clientUserId = config.getClientUserId();
        config.messageFilter = false;
        config.messageFilterMode = 0;
        config.saveConfig(false);
        synchronized (lock) {
            stateLocked(account).clear();
        }
        if (clientUserId > 0) {
            OpengramSnapshotFile.delete(file(clientUserId));
            OpengramStrongBox.forFilter(clientUserId).deleteKey();
        }
        notifyDialogs(account);
    }

    public static boolean saveKeywords(int account, String raw) {
        if (phase(account) != Phase.READY) {
            return false;
        }
        UserConfig config = UserConfig.getInstance(account);
        long clientUserId = config.getClientUserId();
        int mode = config.messageFilterMode;
        if (clientUserId <= 0) {
            return false;
        }
        List<String> keywords = parseKeywords(raw);
        OpengramSnapshotFile.Sealer sealer = mode == OpengramSnapshotFile.MODE_STRONGBOX ? OpengramStrongBox.forFilter(clientUserId) : null;
        if (!writeKeywords(file(clientUserId), mode, clientUserId, keywords, sealer)) {
            return false;
        }
        synchronized (lock) {
            State state = stateLocked(account);
            state.loaded = true;
            state.error = false;
            state.clientUserId = clientUserId;
            state.mode = mode;
            state.replaceKeywords(keywords);
        }
        notifyDialogs(account);
        return true;
    }

    private static void notifyDialogs(int account) {
        MessagesController.getInstance(account).clearVisibleDialogPreviews();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageFilterChanged);
        NotificationsController.getInstance(account).removeFilteredPushMessages();
    }

    public static ArrayList<MessageObject> withoutHidden(int account, long dialogId, ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<String> keywords = keywordsForHide(account);
        if (keywords.isEmpty() || !appliesToDialog(DialogObject.isEncryptedDialog(dialogId), isBroadcastChannel(account, dialogId, null), isBotChat(account, dialogId))) {
            return messages;
        }
        HashSet<Long> hiddenGroups = hiddenGroups(messages, keywords);
        ArrayList<MessageObject> visible = null;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message != null && (messageMatchesPrepared(message, keywords) || hiddenGroups.contains(message.getGroupId()))) {
                if (visible == null) {
                    visible = new ArrayList<>(Math.max(0, messages.size() - 1));
                    for (int j = 0; j < i; j++) {
                        visible.add(messages.get(j));
                    }
                }
                continue;
            }
            if (visible != null) {
                visible.add(message);
            }
        }
        return visible == null ? messages : visible;
    }

    public static boolean previewIsHidden(int account, long dialogId, List<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        HashSet<Long> groups = hiddenGroups(account, dialogId, messages);
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message == null) {
                continue;
            }
            long groupId = message.getGroupId();
            if (shouldHide(account, dialogId, message) || groupId != 0 && groups.contains(groupId)) {
                return true;
            }
        }
        return false;
    }

    public static HashSet<Long> hiddenGroups(int account, long dialogId, List<MessageObject> messages) {
        List<String> keywords = keywordsForHide(account);
        if (keywords.isEmpty() || messages == null || !appliesToDialog(DialogObject.isEncryptedDialog(dialogId), isBroadcastChannel(account, dialogId, null), isBotChat(account, dialogId))) {
            return new HashSet<>();
        }
        return hiddenGroups(messages, keywords);
    }

    public static boolean shouldHide(int account, long dialogId, MessageObject message) {
        if (message == null) {
            return false;
        }
        List<String> keywords = keywordsForHide(account);
        if (keywords.isEmpty()) {
            return false;
        }
        long id = dialogId;
        if (id == 0 && message.messageOwner != null) {
            id = message.getDialogId();
        }
        boolean encrypted = DialogObject.isEncryptedDialog(id);
        boolean userDialog = !encrypted && DialogObject.isUserDialog(id);
        TLRPC.User user = userDialog ? MessagesController.getInstance(account).getUser(id) : null;
        if (holdUntilUserLoaded(true, userDialog, user)) {
            return true;
        }
        if (!appliesToDialog(encrypted, isBroadcastChannel(account, id, message), user != null && user.bot)) {
            return false;
        }
        return messageMatchesPrepared(message, keywords);
    }

    private static HashSet<Long> hiddenGroups(List<MessageObject> messages, List<String> keywords) {
        HashSet<Long> groups = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message == null) {
                continue;
            }
            long groupId = message.getGroupId();
            if (groupId != 0 && messageMatchesPrepared(message, keywords)) {
                groups.add(groupId);
            }
        }
        return groups;
    }

    private static List<String> keywordsForHide(int account) {
        if (phase(account) != Phase.READY) {
            return Collections.emptyList();
        }
        synchronized (lock) {
            return new ArrayList<>(stateLocked(account).folded);
        }
    }

    public static void preload(int account) {
        if (!validAccount(account)) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            synchronized (lock) {
                ensureLoadedLocked(account);
            }
        });
    }

    static boolean missingChatIsChannel(TLRPC.Chat chat, boolean localChannel, boolean localSupergroup) {
        if (chat != null) {
            return chat.broadcast;
        }
        return localChannel && !localSupergroup;
    }

    static boolean holdUntilUserLoaded(boolean filterOn, boolean userDialog, TLRPC.User user) {
        return filterOn && userDialog && user == null;
    }

    private static boolean isBroadcastChannel(int account, long dialogId, MessageObject message) {
        if (!DialogObject.isChatDialog(dialogId)) {
            return false;
        }
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        return missingChatIsChannel(chat, message != null && message.localChannel, message != null && message.localSupergroup);
    }

    private static boolean isBotChat(int account, long dialogId) {
        if (!DialogObject.isUserDialog(dialogId) || DialogObject.isEncryptedDialog(dialogId)) {
            return false;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        return user != null && user.bot;
    }

    private static void ensureLoadedLocked(int account) {
        UserConfig config = UserConfig.getInstance(account);
        State state = stateLocked(account);
        if (!config.messageFilter) {
            state.clear();
            return;
        }
        long clientUserId = config.getClientUserId();
        int mode = config.messageFilterMode;
        if (state.loaded && state.clientUserId == clientUserId && state.mode == mode) {
            return;
        }
        if (clientUserId <= 0 || (mode != OpengramSnapshotFile.MODE_STRONGBOX && mode != OpengramSnapshotFile.MODE_PLAINTEXT)) {
            state.fail(clientUserId, mode);
            return;
        }
        OpengramSnapshotFile.Sealer sealer = mode == OpengramSnapshotFile.MODE_STRONGBOX ? OpengramStrongBox.forFilter(clientUserId) : null;
        Snapshot snapshot = readKeywords(file(clientUserId), mode, clientUserId, sealer);
        if (snapshot.status != OpengramSnapshotFile.Status.OK) {
            state.fail(clientUserId, mode);
            return;
        }
        state.loaded = true;
        state.error = false;
        state.clientUserId = clientUserId;
        state.mode = mode;
        state.replaceKeywords(snapshot.keywords);
    }

    private static boolean validAccount(int account) {
        return account >= 0 && account < ACCOUNT_SLOTS && account < UserConfig.MAX_ACCOUNT_COUNT;
    }

    private static State stateLocked(int account) {
        if (states[account] == null) {
            states[account] = new State();
        }
        return states[account];
    }

    private static File file(long clientUserId) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        return new File(context.getNoBackupFilesDir(), FILE_PREFIX + clientUserId + FILE_SUFFIX);
    }
}
