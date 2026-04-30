## GameEngineOptimizer

**GameEngineOptimizer** is a specialized **Decision Engine** designed to identify and validate the most effective strategies for the game [SixDiceColorYahtzee](https://github.com/samikump/SixDiceColorYahtzee). 

Because the game's decision-making search space is too large for a brute-force approach, this engine uses Java to solve the optimization problem through large-scale simulation and statistical weighting.
<br />
By processing tens of thousands of games, it derives an "Optimized Strategy" that mathematically outperforms standard playstyles.

### 🚀 How it Works

The optimization process follows a rigorous four-step pipeline:

1. **Baseline Simulation**  
   Runs 20,000 games for each of the core base strategies to establish a performance floor:
   * **UPPER PRIORITY**: Focusing on the top section of the scorecard.
   * **COLOR PRIORITY**: Prioritizing color-based scoring.
   * **HYBRID PRIORITY**: A mix of upper priority and color priority.
   * **GAMBLE**: High-risk, high-reward decision-making.
   * **BALANCED**: A stable, middle-ground approach.

2. **Strategy Refinement**  
   Identifies the top-performing base strategy and calculates specific weights for scoring categories. These weights are combined with the base logic to create a unique **Optimized Strategy**.

3. **Validation**  
   Runs an additional 20,000 games specifically for the Optimized Strategy to statistically confirm that the improvements actually lead to higher scores.

4. **Optimization Report**  
   Generates a detailed final analysis consisting of:
   * **Strategy Performance Analysis**: Comparative data across all models, including the Optimized Strategy
   * **Expected Value (EV) Analysis**: Data on key scoring categories.
   * **Final Decision Matrix**: Specific situational recommendations for optimal play.

### 🛠️ Built With
* **Java 25**
* **JavaFX 23** (GUI)
* **MySQL** & **HikariCP** (Data Persistence & Connection Pooling)
* **Maven** (Project Management)

### 📋 Requirements
* **JDK 25** or higher
* A running **MySQL** instance (for data storage)
* Any Java-compatible IDE (NetBeans preferred)

### 📖 Usage
1. Clone the repository.
2. Rename the dbconfig.properties.example file as dbconfig.properties and edit it by replacing yourPasswordHere with the root password of your MySQL database.
3. Build the project using Maven.
4. Run the GameEngineOptimizer class to begin the simulation and generate the report.


TODO: Fix the INSUFFICIENT data in the Final Decision Matrix
