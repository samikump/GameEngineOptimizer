package none.gameengineoptimizer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.FileWriter;
import java.io.IOException;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public class GameEngineOptimizer extends Application {

    private DatabaseManager dbManager = new DatabaseManager();
    private GameEngine engine = new GameEngine();
    
    private Label gamesPlayedLabel = new Label("Number of games played: 0");
    private Label highestScoreLabel = new Label("Highest score so far: 0");
    private Label lowestScoreLabel = new Label("Lowest score so far: 0");
    private Label meanLabel = new Label("Mean: 0.00");
    private Label errorLabel = new Label("");
    private Label statusLabel = new Label("Ready");
    private ProgressBar progressBar = new ProgressBar(0);
    private Button startButton = new Button("Start Simulation");
    private TextArea reportArea = new TextArea();

    private final int GAMES_PER_STRATEGY = 20000; 
    private int totalGamesExpected;
    
    private AtomicInteger gamesCompleted = new AtomicInteger(0);
    private AtomicInteger gamesFailed = new AtomicInteger(0);
    private AtomicInteger highestScore = new AtomicInteger(Integer.MIN_VALUE);
    private AtomicInteger lowestScore = new AtomicInteger(Integer.MAX_VALUE);
    private LongAdder totalScoreSum = new LongAdder();

    private final Semaphore dbWriteSemaphore = new Semaphore(15);

    @Override
    public void start(Stage primaryStage) {
        VBox root = new VBox(10);
        root.setPadding(new Insets(20));
        
        reportArea.setEditable(false);
        reportArea.setPrefHeight(400);
        reportArea.setFont(javafx.scene.text.Font.font("Monospaced", 12));
        
        errorLabel.setStyle("-fx-text-fill: red;");
        statusLabel.setStyle("-fx-font-weight: bold;");
        startButton.setOnAction(e -> startSimulation());

        root.getChildren().addAll(
                new Label("Monte Carlo Game Engine Optimizer"),
                gamesPlayedLabel, highestScoreLabel, lowestScoreLabel, meanLabel, errorLabel,
                statusLabel, progressBar, startButton, new Label("Final Optimization Report:"), reportArea
        );

        Scene scene = new Scene(root, 800, 800);
        primaryStage.setTitle("Game Engine Optimizer - Monte Carlo Analysis");
        primaryStage.setScene(scene);
        primaryStage.show();

        try {
            dbManager.initDatabase();
        } catch (SQLException ex) {
            ex.printStackTrace();
            reportArea.setText("Database Error: " + ex.getMessage());
        }
    } // start method ends here

    @Override
    public void stop() {
        dbManager.close();
    } // stop method ends here

    private void startSimulation() {
        startButton.setDisable(true);
        gamesCompleted.set(0);
        gamesFailed.set(0);
        highestScore.set(Integer.MIN_VALUE);
        lowestScore.set(Integer.MAX_VALUE);
        totalScoreSum.reset();
        reportArea.clear();
        errorLabel.setText("");
        
        GameEngine.Strategy[] allStrategies = GameEngine.Strategy.values();
        totalGamesExpected = GAMES_PER_STRATEGY * allStrategies.length;

        new Thread(() -> {
            try {
                dbManager.clearData();
                
                // Phase 1: Baseline Strategies
                Platform.runLater(() -> statusLabel.setText("Phase 1: Running Baseline Strategies..."));
                List<GameEngine.Strategy> baselines = Arrays.stream(allStrategies)
                        .filter(s -> s != GameEngine.Strategy.OPTIMIZED_VALIDATION)
                        .toList();
                
                runStrategyBatch(baselines);

                // Phase 2: Calculate Weights and Run Optimized Strategy
                Platform.runLater(() -> statusLabel.setText("Phase 2: Calculating Optimal Weights..."));
                Map<String, Double> weights = calculateOptimalWeights();
                engine.setDynamicWeights(weights);
                
                Platform.runLater(() -> statusLabel.setText("Phase 3: Running Optimized Validation..."));
                runStrategyBatch(List.of(GameEngine.Strategy.OPTIMIZED_VALIDATION));

                Platform.runLater(() -> statusLabel.setText("Generating Final Optimization Report... (Please wait)"));
                generateReport();
            } catch (SQLException ex) {
                ex.printStackTrace();
                Platform.runLater(() -> {
                    reportArea.setText("Database Error during simulation: " + ex.getMessage());
                    statusLabel.setText("Error occurred.");
                    startButton.setDisable(false);
                });
            }
        }).start();
    } // startSimulation method ends here

    private void runStrategyBatch(List<GameEngine.Strategy> strategies) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (GameEngine.Strategy strategy : strategies) {
                for (int i = 0; i < GAMES_PER_STRATEGY; i++) {
                    executor.submit(() -> {
                        try {
                            GameEngine.GameResult result = engine.playSingleGame(strategy);
                            dbWriteSemaphore.acquire();
                            try {
                                if (saveResultToDatabase(result)) {
                                    updateStats(result.finalScore);
                                } else {
                                    gamesFailed.incrementAndGet();
                                }
                            } finally {
                                dbWriteSemaphore.release();
                            }
                        } catch (Exception ex) {
                            gamesFailed.incrementAndGet();
                            ex.printStackTrace();
                        }
                    });
                }
            }
        }
    } // runStrategyBatch ends here

    private Map<String, Double> calculateOptimalWeights() throws SQLException {
        Map<String, Double> weights = new HashMap<>();
        
        double globalAvg = 0;
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT AVG(final_score) FROM games")) {
            if (rs.next()) globalAvg = rs.getDouble(1);
        }

        if (globalAvg == 0) return weights;

        // New Logic: Weight = (Avg Final Score when category > 0) / Global Average
        String sql = "SELECT category, AVG(final_score) as cat_avg " +
                     "FROM turns t JOIN games g ON t.game_id = g.id " +
                     "WHERE score > 0 " +
                     "GROUP BY category";
        
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                String cat = rs.getString("category");
                double catAvg = rs.getDouble("cat_avg");
                
                double weight = catAvg / globalAvg;
                
                // Heuristic: Boost bonus sections as they are the key to high scores
                if (isUpper(cat) || isColor(cat)) weight *= 1.2;
                
                weights.put(cat, weight);
            }
        }
        return weights;
    } // calculateOptimalWeights method ends here

    private boolean isUpper(String cat) {
        return Arrays.asList("Ones", "Twos", "Threes", "Fours", "Fives", "Sixes").contains(cat);
    } // isUpper method ends here

    private boolean isColor(String cat) {
        return Arrays.asList("Purples", "Reds", "Oranges", "Yellows", "Greens", "Blues").contains(cat);
    } // isColor method ends here

    private boolean saveResultToDatabase(GameEngine.GameResult result) {
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            String gameSql = "INSERT INTO games (strategy, final_score, yahtzee_bonuses, is_validation) VALUES (?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(gameSql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, result.strategy.name());
                pstmt.setInt(2, result.finalScore);
                pstmt.setInt(3, result.yahtzeeBonuses);
                pstmt.setBoolean(4, result.isValidation);
                pstmt.executeUpdate();
                
                ResultSet rs = pstmt.getGeneratedKeys();
                if (rs.next()) {
                    int gameId = rs.getInt(1);
                    String turnSql = "INSERT INTO turns (game_id, turn_index, category, score, dice_values, roll_index, is_joker) VALUES (?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement turnPstmt = conn.prepareStatement(turnSql, Statement.RETURN_GENERATED_KEYS)) {
                        for (GameEngine.TurnRecord turn : result.turns) {
                            turnPstmt.setInt(1, gameId);
                            turnPstmt.setInt(2, turn.turnIndex);
                            turnPstmt.setString(3, turn.category);
                            turnPstmt.setInt(4, turn.score);
                            turnPstmt.setString(5, turn.diceValues);
                            turnPstmt.setInt(6, turn.rollIndex);
                            turnPstmt.setBoolean(7, turn.isJoker);
                            turnPstmt.executeUpdate();
                            
                            ResultSet rsTurn = turnPstmt.getGeneratedKeys();
                            if (rsTurn.next()) {
                                int turnId = rsTurn.getInt(1);
                                String rollSql = "INSERT INTO roll_states (turn_id, roll_index, best_category, best_score) VALUES (?, ?, ?, ?)";
                                try (PreparedStatement rollPstmt = conn.prepareStatement(rollSql)) {
                                    for (GameEngine.RollState rsState : turn.rollStates) {
                                        rollPstmt.setInt(1, turnId);
                                        rollPstmt.setInt(2, rsState.rollIndex);
                                        rollPstmt.setString(3, rsState.bestCategory);
                                        rollPstmt.setInt(4, rsState.bestScore);
                                        rollPstmt.addBatch();
                                    }
                                    rollPstmt.executeBatch();
                                }
                            }
                        }
                    }
                }
            }
            conn.commit();
            return true;
        } catch (SQLException e) {
            System.err.println("Failed to save game: " + e.getMessage());
            return false;
        }
    } // saveResultsToDatabase method ends here

    private void updateStats(int score) {
        int currentGames = gamesCompleted.incrementAndGet();
        totalScoreSum.add(score);
        
        highestScore.accumulateAndGet(score, Math::max);
        lowestScore.accumulateAndGet(score, Math::min);

        double mean = (double) totalScoreSum.sum() / currentGames;
        int failed = gamesFailed.get();
        
        Platform.runLater(() -> {
            gamesPlayedLabel.setText("Number of games played: " + currentGames);
            highestScoreLabel.setText("Highest score so far: " + highestScore.get());
            lowestScoreLabel.setText("Lowest score so far: " + lowestScore.get());
            meanLabel.setText(String.format("Mean: %.2f", mean));
            if (failed > 0) {
                errorLabel.setText("Failed saves: " + failed);
            }
            progressBar.setProgress((double) (currentGames + failed) / totalGamesExpected);
        });
    } // updateStats method ends here

    private void generateReport() {
        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            report.append("============================================================\n");
            report.append("   SIX DICE COLOR YAHTZEE - ADVANCED OPTIMIZATION REPORT\n");
            report.append("   VERSION: 2.9 (Deep Monte Carlo Analysis)\n");
            report.append("============================================================\n\n");

            try (Connection conn = dbManager.getConnection()) {
                Statement stmt = conn.createStatement();
                
                report.append("--- Strategy Performance Analysis ---\n");
                try (ResultSet rs = stmt.executeQuery("SELECT strategy, AVG(final_score) as avg_score, MAX(final_score) as max_score, " +
                                     "STDDEV(final_score) as std_dev FROM games GROUP BY strategy ORDER BY avg_score DESC")) {
                    while (rs.next()) {
                        report.append(String.format("Strategy: %-20s | Avg: %7.2f | Max: %4d | SD: %5.2f\n", 
                                rs.getString("strategy"), rs.getDouble("avg_score"), rs.getInt("max_score"), rs.getDouble("std_dev")));
                    }
                }

                report.append("\n--- Expected Value (EV) Analysis by Roll ---\n");
                String[] categories = {"Yahtzee", "Huge Straight", "Flush", "Extended Full House", "Extended Painted House"};
                for (String cat : categories) {
                    report.append(String.format("[%s]\n", cat));
                    for (int roll = 1; roll <= 3; roll++) {
                        String condition = String.format("category='%s' AND roll_index=%d", cat, roll);
                        appendEVAnalysis(stmt, report, condition, "Roll " + roll, cat);
                    }
                    report.append("\n");
                }

                report.append("--- Subsequent Yahtzee (Joker) Pursuit ---\n");
                String successJoker = "yahtzee_bonuses > 0";
                String failureJoker = "yahtzee_bonuses = 0";
                Double meanSJ = getMean(stmt, "final_score", successJoker);
                Double meanFJ = getMean(stmt, "final_score", failureJoker);
                int countSJ = getCount(stmt, successJoker);
                int countFJ = getCount(stmt, failureJoker);
                report.append(String.format("   EV with Bonuses: %s (n=%-6d) vs EV without: %s (n=%-6d)\n", 
                                           formatEV(meanSJ), countSJ, formatEV(meanFJ), countFJ));

                report.append("\n--- Validation of Optimized Strategy ---\n");
                try (ResultSet rs = stmt.executeQuery("SELECT AVG(final_score) FROM games WHERE strategy = 'OPTIMIZED_VALIDATION'")) {
                    if (rs.next() && rs.getObject(1) != null) {
                        double optMean = rs.getDouble(1);
                        try (ResultSet rs2 = stmt.executeQuery("SELECT AVG(final_score) FROM games WHERE strategy != 'OPTIMIZED_VALIDATION'")) {
                            rs2.next();
                            double globalMean = rs2.getDouble(1);
                            report.append(String.format("Optimized Strategy Mean: %.2f\n", optMean));
                            report.append(String.format("Baseline Strategies Mean: %.2f\n", globalMean));
                            report.append(String.format("Improvement: %+.2f points (%.2f%%)\n", 
                                    optMean - globalMean, (optMean - globalMean) / globalMean * 100));
                        }
                    }
                }

                report.append("\n--- Final Decision Matrix (Roll-Specific) ---\n");
                report.append(String.format("%-50s| %-25s\n", "Condition", "Action"));
                report.append("--------------------------------------------------|------------------------\n");
                
                report.append(String.format("%-50s| %-25s\n", "5 of a Kind (Roll 1) [Risk of Zero]", 
                        getStayOrGoRecommendation(stmt, "rs.best_category='5 of a Kind (Risky)' AND rs.roll_index=1", 40, "Pursue Yahtzee", "Pivot to Other Category")));

                report.append(String.format("%-50s| %-25s\n", "5 of a Kind (Roll 2) [Risk of Zero]", 
                        getStayOrGoRecommendation(stmt, "rs.best_category='5 of a Kind (Risky)' AND rs.roll_index=2", 40, "Pursue Yahtzee", "Pivot to Other Category")));
                
                report.append(String.format("%-50s| %-25s\n", "Large Straight (Roll 1) [Risk of Zero]", 
                        getStayOrGoRecommendation(stmt, "rs.best_category='Large Straight (Risky)' AND rs.roll_index=1", 30, "Pursue Huge Straight", "Pivot to Other Category")));

                report.append(String.format("%-50s| %-25s\n", "Large Straight (Roll 2) [Risk of Zero]", 
                        getStayOrGoRecommendation(stmt, "rs.best_category='Large Straight (Risky)' AND rs.roll_index=2", 30, "Pursue Huge Straight", "Pivot to Other Category")));
                
                report.append(String.format("%-50s| %-25s\n", "5 of a Color (Roll 1) [Risk of Zero]", 
                        getStayOrGoRecommendation(stmt, "rs.best_category='5 of a Color (Risky)' AND rs.roll_index=1", 20, "Pursue Flush", "Pivot to Other Category")));

                report.append(String.format("%-50s| %-25s\n", "5 of a Color (Roll 2) [Risk of Zero]", 
                        getStayOrGoRecommendation(stmt, "rs.best_category='5 of a Color (Risky)' AND rs.roll_index=2", 20, "Pursue Flush", "Pivot to Other Category")));
                
                report.append(String.format("%-50s| %-25s\n", "Ext. Full House (Roll 1) [Yahtzee Unfilled]", 
                        getStayOrGoRecommendation(stmt, "rs.best_category='Extended Full House' AND rs.roll_index=1 AND g.id NOT IN (SELECT game_id FROM turns WHERE category='Yahtzee')", 40, "Pursue Yahtzee", "Keep Ext. FH")));

                report.append(String.format("%-50s| %-25s\n", "Ext. Full House (Roll 1) [Yahtzee Filled]", 
                        getStayOrGoRecommendation(stmt, "rs.best_category='Extended Full House' AND rs.roll_index=1 AND g.id IN (SELECT game_id FROM turns WHERE category='Yahtzee' AND score=50)", 60, "Pursue Yahtzee", "Keep Ext. FH")));
                
                report.append(String.format("%-50s| %-25s\n", "Ext. Painted House (Roll 1)", 
                        getStayOrGoRecommendation(stmt, "rs.best_category='Extended Painted House' AND rs.roll_index=1", 40, "Pursue Flush", "Keep Ext. PH")));

            } catch (SQLException e) {
                report.append("\n[!] Error generating report: ").append(e.getMessage());
                e.printStackTrace();
            }

            report.append("\n============================================================\n");
            SimpleDateFormat sdf = new SimpleDateFormat("EEE yyyy MMM dd HH:mm:ss z");
            report.append("   REPORT GENERATED ON: " + sdf.format(new java.util.Date()) + "\n");
            report.append("============================================================\n");

            String reportText = report.toString();
            Platform.runLater(() -> {
                reportArea.setText(reportText);
                statusLabel.setText("Report generated successfully.");
                startButton.setDisable(false);
            });
            
            try (FileWriter writer = new FileWriter("optimization_report.txt")) {
                writer.write(reportText);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    } // generateReport method ends here

    private void appendEVAnalysis(Statement stmt, StringBuilder report, String condition, String label, String cat) throws SQLException {
        String success = "id IN (SELECT game_id FROM turns WHERE " + condition + " AND score > 0)";
        String failure = "id IN (SELECT game_id FROM turns WHERE category='" + cat + "' AND score = 0)";
        
        Double meanS = getMean(stmt, "final_score", success);
        Double meanF = getMean(stmt, "final_score", failure);
        int countS = getCount(stmt, success);
        int countF = getCount(stmt, failure);

        if (countF == 0) {
            failure = "id IN (SELECT game_id FROM turns WHERE score = 0)";
            meanF = getMean(stmt, "final_score", failure);
            countF = getCount(stmt, failure);
        }

        report.append(String.format("   %-8s: EV Success: %s (n=%-6d) | EV Sacrifice: %s (n=%-6d)\n", 
                                   label, formatEV(meanS), countS, formatEV(meanF), countF));
    } // appendEVAnalysis ends here

    private String getStayOrGoRecommendation(Statement stmt, String condition, double threshold, String goAction, String stayAction) throws SQLException {
        // EV when we 'Stay' (take the category immediately or pivot if risky)
        String stayQuery = "id IN (SELECT game_id FROM games g JOIN turns t ON g.id = t.game_id JOIN roll_states rs ON t.id = rs.turn_id " +
                           "WHERE " + condition + " AND t.roll_index = rs.roll_index)";
        
        // EV when we 'Go' (roll again despite having the category)
        String goQuery = "id IN (SELECT game_id FROM games g JOIN turns t ON g.id = t.game_id JOIN roll_states rs ON t.id = rs.turn_id " +
                         "WHERE " + condition + " AND t.roll_index > rs.roll_index)";
        
        Double meanStay = getMean(stmt, "final_score", stayQuery);
        Double meanGo = getMean(stmt, "final_score", goQuery);
        
        if (meanStay != null && meanGo != null) {
            return (meanGo - meanStay > threshold) ? goAction : stayAction;
        }
        
        return "INSUFFICIENT DATA";
    } // getStayOrGoRecommendation method ends here

    private Double getMean(Statement stmt, String column, String condition) throws SQLException {
        String sql = "SELECT AVG(" + column + ") FROM games WHERE " + condition;
        try (ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                double val = rs.getDouble(1);
                if (rs.wasNull()) return null;
                return val;
            }
        }
        return null;
    } // getMean method ends here

    private String formatEV(Double val) {
        return (val == null) ? "  N/A  " : String.format("%7.2f", val);
    } // formatEV ends here

    private int getCount(Statement stmt, String condition) throws SQLException {
        String sql = "SELECT COUNT(*) FROM games WHERE " + condition;
        try (ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        }
        return 0;
    } // getCount method ends here

    public static void main(String[] args) {
        launch(args);
    } // main method ends here
} // class GameEngineOptimizer ends here
