package none.gameengineoptimizer;

import java.util.*;
import java.util.stream.Collectors;

public class GameEngine {
    public static final String[] COLORS = {"purple", "red", "orange", "yellow", "green", "blue"};
    
    public enum Strategy {
        BALANCED, UPPER_PRIORITY, COLOR_PRIORITY, HYBRID_PRIORITY, GAMBLE, OPTIMIZED_VALIDATION
    }

    private Map<String, Double> dynamicWeights = new HashMap<>();

    public void setDynamicWeights(Map<String, Double> weights) {
        this.dynamicWeights = weights;
    }

    public static class GameResult {
        public int finalScore;
        public List<TurnRecord> turns;
        public Strategy strategy;
        public int yahtzeeBonuses;
        public boolean isValidation;
        
        public GameResult(int finalScore, List<TurnRecord> turns, Strategy strategy, int yahtzeeBonuses, boolean isValidation) {
            this.finalScore = finalScore;
            this.turns = turns;
            this.strategy = strategy;
            this.yahtzeeBonuses = yahtzeeBonuses;
            this.isValidation = isValidation;
        }
    }

    public static class TurnRecord {
        public int turnIndex;
        public String category;
        public int score;
        public String diceValues;
        public int rollIndex;
        public boolean isJoker;
        public List<RollState> rollStates = new ArrayList<>();

        public TurnRecord(int turnIndex, String category, int score, int[] dice, int rollIndex, boolean isJoker) {
            this.turnIndex = turnIndex;
            this.category = category;
            this.score = score;
            this.diceValues = Arrays.toString(dice);
            this.rollIndex = rollIndex;
            this.isJoker = isJoker;
        }
    }

    public static class RollState {
        public int rollIndex;
        public String bestCategory;
        public int bestScore;

        public RollState(int rollIndex, String bestCategory, int bestScore) {
            this.rollIndex = rollIndex;
            this.bestCategory = bestCategory;
            this.bestScore = bestScore;
        }
    }

    public GameResult playSingleGame(Strategy strategy) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        Set<String> filledCells = new HashSet<>();
        List<TurnRecord> turnRecords = new ArrayList<>();
        int yahtzeeBonuses = 0;
        boolean isValidation = (strategy == Strategy.OPTIMIZED_VALIDATION);
        Random rand = new Random();
        
        List<String> allCategories = Arrays.asList(
            "Ones", "Twos", "Threes", "Fours", "Fives", "Sixes",
            "Purples", "Reds", "Oranges", "Yellows", "Greens", "Blues",
            "Pair", "2 Pairs", "3 Pairs", "3 of a Kind", "2 * 3 of a Kind", "4 of a Kind", "5 of a Kind",
            "Small Straight", "Large Straight", "Huge Straight", "Full House", "Extended Full House",
            "3 of a Color", "2 * 3 of a Color", "4 of a Color", "5 of a Color", "Painted House",
            "Extended Painted House", "Rainbow", "Flush", "Yahtzee", "Chance"
        );

        for (int i = 0; i < allCategories.size(); i++) {
            int[] diceValues = new int[6];
            String[] diceColors = new String[6];
            boolean[] held = new boolean[6];
            int finalRollIndex = 0;
            List<RollState> currentTurnRolls = new ArrayList<>();

            for (int roll = 0; roll < 4; roll++) {
                finalRollIndex = roll + 1;
                for (int j = 0; j < 6; j++) {
                    if (!held[j]) {
                        diceValues[j] = rand.nextInt(6) + 1;
                        diceColors[j] = COLORS[(j + diceValues[j] - 1) % 6];
                    }
                }

                // Analyze current roll potential
                String bestAtRoll = null;
                int bestScoreAtRoll = -1;
                boolean yahtzeeFilled = filledCells.contains("Yahtzee");
                boolean isYahtzeeNow = isYahtzee(diceValues);
                
                if (isYahtzeeNow && yahtzeeFilled && scores.getOrDefault("Yahtzee", 0) == 50) {
                    bestAtRoll = "Subsequent Yahtzee";
                    bestScoreAtRoll = 100;
                } else {
                    for (String cat : allCategories) {
                        if (!filledCells.contains(cat)) {
                            int s = calculateScore(cat, diceValues, diceColors, false);
                            if (s > bestScoreAtRoll) {
                                bestScoreAtRoll = s;
                                bestAtRoll = cat;
                            }
                        }
                    }
                    
                    // Special detection for 'Risk of Zero' situations (for Optimizer)
                    Map<Integer, Long> counts = Arrays.stream(diceValues).boxed().collect(Collectors.groupingBy(v -> v, Collectors.counting()));
                    Optional<Map.Entry<Integer, Long>> maxE = counts.entrySet().stream().max(Map.Entry.comparingByValue());
                    if (maxE.isPresent() && maxE.get().getValue() == 5) {
                        int val = maxE.get().getKey();
                        String upperCat = getUpperCatForValue(val);
                        // Risk of Zero: Both 5 of a Kind and the Upper cell are filled, but Yahtzee is open.
                        if (filledCells.contains("5 of a Kind") && filledCells.contains(upperCat) && !filledCells.contains("Yahtzee")) {
                            bestAtRoll = "5 of a Kind (Risky)";
                            bestScoreAtRoll = val * 5;
                        }
                    }

                    Map<String, Long> colCountsInner = Arrays.stream(diceColors).collect(Collectors.groupingBy(c -> c, Collectors.counting()));
                    Optional<Map.Entry<String, Long>> maxC = colCountsInner.entrySet().stream().max(Map.Entry.comparingByValue());
                    if (maxC.isPresent() && maxC.get().getValue() == 5) {
                        String col = maxC.get().getKey();
                        String colorCat = getColorCatForValue(col);
                        // Risk of Zero: Both 5 of a Color and the Color cell are filled, but Flush is open.
                        if (filledCells.contains("5 of a Color") && filledCells.contains(colorCat) && !filledCells.contains("Flush")) {
                            bestAtRoll = "5 of a Color (Risky)";
                            bestScoreAtRoll = sumByColor(col, diceValues, diceColors);
                        }
                    }

                    if (isStraight(diceValues, 5)) {
                        // Risk of Zero: Both Large and Small Straights are filled, but Huge Straight is open.
                        if (filledCells.contains("Large Straight") && filledCells.contains("Small Straight") && !filledCells.contains("Huge Straight")) {
                            bestAtRoll = "Large Straight (Risky)";
                            bestScoreAtRoll = 20;
                        }
                    }
                }
                currentTurnRolls.add(new RollState(finalRollIndex, bestAtRoll, bestScoreAtRoll));

                // Decision to stop or roll again
                boolean shouldStop = false;
                if (bestScoreAtRoll > 0) {
                    if (bestAtRoll.equals("Yahtzee") || bestAtRoll.equals("Huge Straight") || bestAtRoll.equals("Flush") || bestAtRoll.equals("Subsequent Yahtzee")) {
                        shouldStop = true;
                        // GAMBLE strategy: 30% chance to ignore high-value categories on Roll 1 or 2
                        if (strategy == Strategy.GAMBLE && roll < 2 && rand.nextDouble() < 0.3) {
                            shouldStop = false;
                        }
                    } else if (bestAtRoll.contains("Extended") && roll < 2) {
                        shouldStop = true;
                        if (strategy == Strategy.GAMBLE && rand.nextDouble() < 0.5) {
                            shouldStop = false;
                        }
                    } else if (bestAtRoll.contains("(Risky)") && strategy == Strategy.BALANCED && rand.nextDouble() < 0.5) {
                        // Specifically generate data for "Pivot" (Stay) in risky situations
                        shouldStop = true;
                    } else if (strategy == Strategy.BALANCED && rand.nextDouble() < 0.2) {
                        // Provide baseline "Stay" data for the optimizer by randomly stopping early
                        shouldStop = true;
                    }
                }

                if (shouldStop) break;

                if (roll < 3) {
                    held = decideWhatToHold(diceValues, diceColors, filledCells, allCategories, strategy, scores);
                    boolean allHeld = true;
                    for (boolean h : held) if (!h) { allHeld = false; break; }
                    if (allHeld) break;
                }
            }

            boolean isYahtzee = isYahtzee(diceValues);
            boolean isJoker = false;
            if (isYahtzee && filledCells.contains("Yahtzee") && scores.getOrDefault("Yahtzee", 0) == 50) {
                yahtzeeBonuses++;
                isJoker = true;
            }

            String bestCategory = null;
            double bestWeight = -Double.MAX_VALUE;

            for (String cat : allCategories) {
                if (!filledCells.contains(cat)) {
                    int score = calculateScore(cat, diceValues, diceColors, isJoker);
                    double weight = score;
                    
                    if (strategy == Strategy.UPPER_PRIORITY && isUpper(cat)) weight *= 1.6;
                    if (strategy == Strategy.COLOR_PRIORITY && isColor(cat)) weight *= 1.6;
                    if (strategy == Strategy.HYBRID_PRIORITY && (isUpper(cat) || isColor(cat))) weight *= 1.4;
                    
                    if (isValidation) {
                        double multiplier = dynamicWeights.getOrDefault(cat, 1.0);
                        weight *= multiplier;
                    }
                    
                    if (weight > bestWeight) {
                        bestWeight = weight;
                        bestCategory = cat;
                    }
                }
            }

            int finalTurnScore = calculateScore(bestCategory, diceValues, diceColors, isJoker);
            scores.put(bestCategory, finalTurnScore);
            filledCells.add(bestCategory);
            TurnRecord tr = new TurnRecord(i, bestCategory, finalTurnScore, diceValues, finalRollIndex, isJoker);
            tr.rollStates = currentTurnRolls;
            turnRecords.add(tr);
        }

        return new GameResult(calculateTotal(scores, yahtzeeBonuses), turnRecords, strategy, yahtzeeBonuses, isValidation);
    }

    private boolean[] decideWhatToHold(int[] values, String[] colors, Set<String> filled, List<String> all, Strategy strategy, Map<String, Integer> scores) {
        boolean[] held = new boolean[6];
        Map<Integer, Long> counts = Arrays.stream(values).boxed().collect(Collectors.groupingBy(v -> v, Collectors.counting()));
        Optional<Map.Entry<Integer, Long>> maxEntry = counts.entrySet().stream().max(Map.Entry.comparingByValue());
        
        // 6 of a Kind (Yahtzee) logic
        if (maxEntry.isPresent() && maxEntry.get().getValue() == 6) {
            for (int i = 0; i < 6; i++) held[i] = true;
            return held;
        }

        // 5 of a Kind logic with 'Risk of Zero' consideration
        if (maxEntry.isPresent() && maxEntry.get().getValue() == 5) {
            int val = maxEntry.get().getKey();
            String upperCat = getUpperCatForValue(val);
            // Hold if we can take 5 of a Kind, OR the Upper cat, OR if we are going for Yahtzee.
            if (!filled.contains("5 of a Kind") || !filled.contains(upperCat) || !filled.contains("Yahtzee")) {
                for (int i = 0; i < 6; i++) if (values[i] == val) held[i] = true;
                return held;
            }
        }

        // 5 of a Color logic with 'Risk of Zero' consideration
        Map<String, Long> colCounts = new HashMap<>();
        for (String c : colors) colCounts.put(c, colCounts.getOrDefault(c, 0L) + 1);
        Optional<Map.Entry<String, Long>> maxColEntry = colCounts.entrySet().stream().max(Map.Entry.comparingByValue());
        if (maxColEntry.isPresent() && maxColEntry.get().getValue() == 5) {
            String col = maxColEntry.get().getKey();
            String colorCat = getColorCatForValue(col);
            // Hold if we can take 5 of a Color, OR the Color cat, OR if we are going for Flush.
            if (!filled.contains("5 of a Color") || !filled.contains(colorCat) || !filled.contains("Flush")) {
                for (int i = 0; i < 6; i++) if (colors[i].equals(col)) held[i] = true;
                return held;
            }
        }

        // Straight Pursuit logic (Riskless and Risk of Zero)
        if (isStraight(values, 5) && !filled.contains("Huge Straight")) {
            boolean largeFilled = filled.contains("Large Straight");
            boolean smallFilled = filled.contains("Small Straight");
            // Hold if we can take Large, OR Small, OR if we are going for Huge.
            if (!largeFilled || !smallFilled || !filled.contains("Huge Straight")) {
                Set<Integer> seen = new HashSet<>();
                for (int i = 0; i < 6; i++) {
                    if (!seen.contains(values[i])) { held[i] = true; seen.add(values[i]); }
                }
                return held;
            }
        }

        String target = null;
        double maxW = -1;
        for (String cat : all) {
            if (!filled.contains(cat)) {
                int s = calculateScore(cat, values, colors, false);
                double w = s;
                if (strategy == Strategy.UPPER_PRIORITY && isUpper(cat)) w *= 1.5;
                if (strategy == Strategy.COLOR_PRIORITY && isColor(cat)) w *= 1.5;
                if (strategy == Strategy.HYBRID_PRIORITY && (isUpper(cat) || isColor(cat))) w *= 1.3;
                if (strategy == Strategy.OPTIMIZED_VALIDATION) {
                    w *= dynamicWeights.getOrDefault(cat, 1.1);
                }
                
                if (w > maxW) { maxW = w; target = cat; }
            }
        }

        if (target == null || maxW <= 0) return held;

        if (isUpper(target)) {
            int val = Arrays.asList("Ones", "Twos", "Threes", "Fours", "Fives", "Sixes").indexOf(target) + 1;
            for (int i = 0; i < 6; i++) if (values[i] == val) held[i] = true;
        } else if (isColor(target)) {
            String col = COLORS[Arrays.asList("Purples", "Reds", "Oranges", "Yellows", "Greens", "Blues").indexOf(target)];
            for (int i = 0; i < 6; i++) if (colors[i].equals(col)) held[i] = true;
        } else if (target.contains("Kind") || target.equals("Yahtzee")) {
            int bestV = maxEntry.get().getKey();
            for (int i = 0; i < 6; i++) if (values[i] == bestV) held[i] = true;
        } else if (target.contains("Straight")) {
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < 6; i++) {
                if (!seen.contains(values[i])) { held[i] = true; seen.add(values[i]); }
            }
        } else if (target.equals("Flush")) {
            Map<String, Long> colCountsTarget = Arrays.stream(colors).collect(Collectors.groupingBy(c -> c, Collectors.counting()));
            String bestCol = colCountsTarget.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
            for (int i = 0; i < 6; i++) if (colors[i].equals(bestCol)) held[i] = true;
        } else if (target.contains("Full House")) {
            List<Integer> sortedKeys = counts.entrySet().stream()
                    .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            if (sortedKeys.size() >= 1) {
                int val1 = sortedKeys.get(0);
                for (int i = 0; i < 6; i++) if (values[i] == val1) held[i] = true;
                if (sortedKeys.size() >= 2) {
                    int val2 = sortedKeys.get(1);
                    for (int i = 0; i < 6; i++) if (values[i] == val2) held[i] = true;
                }
            }
        } else if (target.contains("Painted House")) {
            Map<String, Long> colCountsTarget = Arrays.stream(colors).collect(Collectors.groupingBy(c -> c, Collectors.counting()));
            List<String> sortedCols = colCountsTarget.entrySet().stream()
                    .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            if (sortedCols.size() >= 1) {
                String col1 = sortedCols.get(0);
                for (int i = 0; i < 6; i++) if (colors[i].equals(col1)) held[i] = true;
                if (sortedCols.size() >= 2) {
                    String col2 = sortedCols.get(1);
                    for (int i = 0; i < 6; i++) if (colors[i].equals(col2)) held[i] = true;
                }
            }
        }
        
        return held;
    }

    private String getUpperCatForValue(int val) {
        return switch (val) {
            case 1 -> "Ones";
            case 2 -> "Twos";
            case 3 -> "Threes";
            case 4 -> "Fours";
            case 5 -> "Fives";
            case 6 -> "Sixes";
            default -> "";
        };
    }

    private String getColorCatForValue(String col) {
        return switch (col) {
            case "purple" -> "Purples";
            case "red" -> "Reds";
            case "orange" -> "Oranges";
            case "yellow" -> "Yellows";
            case "green" -> "Greens";
            case "blue" -> "Blues";
            default -> "";
        };
    }

    private boolean isUpper(String cat) {
        return Arrays.asList("Ones", "Twos", "Threes", "Fours", "Fives", "Sixes").contains(cat);
    }

    private boolean isColor(String cat) {
        return Arrays.asList("Purples", "Reds", "Oranges", "Yellows", "Greens", "Blues").contains(cat);
    }

    private int calculateTotal(Map<String, Integer> scores, int yahtzeeBonuses) {
        int upperSum = 0;
        String[] upperCats = {"Ones", "Twos", "Threes", "Fours", "Fives", "Sixes"};
        for (String c : upperCats) upperSum += scores.getOrDefault(c, 0);
        int upperBonus = (upperSum >= 84) ? 50 : 0;

        int colorSum = 0;
        String[] colorCats = {"Purples", "Reds", "Oranges", "Yellows", "Greens", "Blues"};
        for (String c : colorCats) colorSum += scores.getOrDefault(c, 0);
        int colorBonus = (colorSum >= 84) ? 50 : 0;

        int total = upperSum + upperBonus + colorSum + colorBonus + (yahtzeeBonuses * 100);
        String[] otherCats = {
            "Pair", "2 Pairs", "3 Pairs", "3 of a Kind", "2 * 3 of a Kind", "4 of a Kind", "5 of a Kind",
            "Small Straight", "Large Straight", "Huge Straight", "Full House", "Extended Full House",
            "3 of a Color", "2 * 3 of a Color", "4 of a Color", "5 of a Color", "Painted House",
            "Extended Painted House", "Rainbow", "Flush", "Yahtzee", "Chance"
        };
        for (String c : otherCats) total += scores.getOrDefault(c, 0);
        
        return total;
    }

    private int calculateScore(String category, int[] values, String[] colors, boolean isSubsequentYahtzee) {
        Map<Integer, Long> valCounts = Arrays.stream(values).boxed()
                .collect(Collectors.groupingBy(v -> v, Collectors.counting()));
        Map<String, Long> colCounts = Arrays.stream(colors)
                .collect(Collectors.groupingBy(c -> c, Collectors.counting()));

        if (isSubsequentYahtzee) {
            if (category.equals("Small Straight")) return 15;
            if (category.equals("Large Straight")) return 20;
            if (category.equals("Huge Straight")) return 35;
            if (category.equals("Full House")) return 25;
            if (category.equals("Extended Full House")) return 35;
        }

        switch (category) {
            case "Ones": return (int) (valCounts.getOrDefault(1, 0L) * 1);
            case "Twos": return (int) (valCounts.getOrDefault(2, 0L) * 2);
            case "Threes": return (int) (valCounts.getOrDefault(3, 0L) * 3);
            case "Fours": return (int) (valCounts.getOrDefault(4, 0L) * 4);
            case "Fives": return (int) (valCounts.getOrDefault(5, 0L) * 5);
            case "Sixes": return (int) (valCounts.getOrDefault(6, 0L) * 6);

            case "Purples": return sumByColor("purple", values, colors);
            case "Reds": return sumByColor("red", values, colors);
            case "Oranges": return sumByColor("orange", values, colors);
            case "Yellows": return sumByColor("yellow", values, colors);
            case "Greens": return sumByColor("green", values, colors);
            case "Blues": return sumByColor("blue", values, colors);

            case "Pair": return getXOfAKind(valCounts, 2) * 2;
            case "2 Pairs": return getTwoPairs(valCounts);
            case "3 Pairs": return getThreePairs(valCounts);
            case "3 of a Kind": return getXOfAKind(valCounts, 3) * 3;
            case "2 * 3 of a Kind": return getTwoThreeOfAKinds(valCounts);
            case "4 of a Kind": return getXOfAKind(valCounts, 4) * 4;
            case "5 of a Kind": return getXOfAKind(valCounts, 5) * 5;
            case "Small Straight": return isStraight(values, 4) ? 15 : 0;
            case "Large Straight": return isStraight(values, 5) ? 20 : 0;
            case "Huge Straight": return isStraight(values, 6) ? 35 : 0;
            case "Full House": return isFullHouse(valCounts, 3, 2) ? 25 : 0;
            case "Extended Full House": return isFullHouse(valCounts, 4, 2) ? 35 : 0;
            case "3 of a Color": return sumOfXOfAColor(colCounts, 3, values, colors);
            case "2 * 3 of a Color": return getTwoThreeOfAColor(colCounts, values, colors);
            case "4 of a Color": return sumOfXOfAColor(colCounts, 4, values, colors);
            case "5 of a Color": return sumOfXOfAColor(colCounts, 5, values, colors);
            case "Painted House": return isPaintedHouse(colCounts, 3, 2) ? 25 : 0;
            case "Extended Painted House": return isPaintedHouse(colCounts, 4, 2) ? 35 : 0;
            case "Rainbow": return colCounts.size() == 6 ? 40 : 0;
            case "Flush": return isFlush(colors) ? 50 : 0;
            case "Yahtzee": return isYahtzee(values) ? 50 : 0;
            case "Chance": return Arrays.stream(values).sum();
            default: return 0;
        }
    }

    private int sumByColor(String color, int[] values, String[] colors) {
        int sum = 0;
        for (int i = 0; i < 6; i++) {
            if (colors[i].equals(color)) sum += values[i];
        }
        return sum;
    }

    private int getXOfAKind(Map<Integer, Long> counts, int x) {
        return counts.entrySet().stream()
                .filter(e -> e.getValue() >= x)
                .mapToInt(Map.Entry::getKey)
                .max().orElse(0);
    }

    private int getTwoPairs(Map<Integer, Long> counts) {
        List<Integer> pairs = counts.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .map(Map.Entry::getKey)
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
        if (pairs.size() >= 2) return pairs.get(0) * 2 + pairs.get(1) * 2;
        return 0;
    }

    private int getThreePairs(Map<Integer, Long> counts) {
        List<Integer> pairs = counts.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        if (pairs.size() == 3) return pairs.stream().mapToInt(i -> i * 2).sum();
        return 0;
    }

    private int getTwoThreeOfAKinds(Map<Integer, Long> counts) {
        List<Integer> triplets = counts.entrySet().stream()
                .filter(e -> e.getValue() >= 3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        if (triplets.size() >= 2) return triplets.get(0) * 3 + triplets.get(1) * 3;
        return 0;
    }

    private int getTwoThreeOfAColor(Map<String, Long> counts, int[] values, String[] colors) {
        List<String> cols = counts.entrySet().stream()
                .filter(e -> e.getValue() >= 3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        if (cols.size() >= 2) {
            int sum = 0;
            for (int i = 0; i < 6; i++) {
                if (cols.contains(colors[i])) sum += values[i];
            }
            return sum;
        }
        return 0;
    }

    private int sumOfXOfAColor(Map<String, Long> counts, int x, int[] values, String[] colors) {
        return counts.entrySet().stream()
                .filter(e -> e.getValue() >= x)
                .mapToInt(e -> {
                    String color = e.getKey();
                    List<Integer> colorValues = new ArrayList<>();
                    for (int i = 0; i < 6; i++) {
                        if (colors[i].equals(color)) colorValues.add(values[i]);
                    }
                    return colorValues.stream()
                            .sorted(Comparator.reverseOrder())
                            .limit(x)
                            .mapToInt(i -> i)
                            .sum();
                })
                .max().orElse(0);
    }

    private boolean isStraight(int[] values, int length) {
        Set<Integer> set = Arrays.stream(values).boxed().collect(Collectors.toSet());
        for (int i = 1; i <= 7 - length; i++) {
            boolean match = true;
            for (int j = 0; j < length; j++) {
                if (!set.contains(i + j)) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }

    private boolean isFullHouse(Map<Integer, Long> counts, int x, int y) {
        List<Long> cList = new ArrayList<>(counts.values());
        Collections.sort(cList, Collections.reverseOrder());
        return cList.size() >= 2 && cList.get(0) >= x && cList.get(1) >= y;
    }

    private boolean isPaintedHouse(Map<String, Long> counts, int x, int y) {
        List<Long> cList = new ArrayList<>(counts.values());
        Collections.sort(cList, Collections.reverseOrder());
        return cList.size() >= 2 && cList.get(0) >= x && cList.get(1) >= y;
    }

    private boolean isYahtzee(int[] values) {
        if (values.length < 6) return false;
        int first = values[0];
        return Arrays.stream(values).allMatch(v -> v == first);
    }

    private boolean isFlush(String[] colors) {
        if (colors.length < 6) return false;
        String first = colors[0];
        return Arrays.stream(colors).allMatch(c -> c.equals(first));
    }
}
