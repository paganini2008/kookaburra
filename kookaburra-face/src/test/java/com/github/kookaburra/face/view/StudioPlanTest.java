package com.github.kookaburra.face.view;

import com.github.kookaburra.face.StudioApplication;
import com.github.kookaburra.face.demo.DemoData;
import com.github.kookaburra.face.model.Session;
import com.github.kookaburra.query.QueryPlan;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query plan diagram of the console, in memory and on disk: Explain (plan only), Analyze (rows and times, the
 * slowest step marked), the text view, plans of vector and full-text searches, UPDATE plans as text, and errors.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class StudioPlanTest {

    private MainView view;

    @Start
    void start(Stage stage) {
        view = new MainView();
        Scene scene = StudioApplication.scene(view);
        stage.setScene(scene);
        stage.setWidth(1320);
        stage.setHeight(820);
        stage.show();
    }

    @AfterEach
    void close() {
        Platform.runLater(view::close);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void settle() {
        for (int i = 0; i < 500 && view.tasks.pending() > 0; i++) {
            WaitForAsyncUtils.sleep(20, TimeUnit.MILLISECONDS);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    private boolean await(BooleanSupplier c) {
        for (int i = 0; i < 250 && !c.getAsBoolean(); i++) {
            WaitForAsyncUtils.sleep(20, TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
        return c.getAsBoolean();
    }

    private void open(FxRobot robot, boolean onDisk, Path tmp) {
        Session s;
        if (onDisk) {
            s = Session.open(tmp.resolve("db"));
            DemoData.fill(s.db(), 40, 7);
            s.flush();
        } else {
            s = Session.demo();
        }
        robot.interact(() -> view.open(s));
        settle();
        robot.interact(() -> view.tabs.getSelectionModel().select(1));
    }

    private void type(FxRobot robot, String oql) {
        robot.interact(() -> view.query.setText(oql));
    }

    private QueryPlan.Step step(int row) {
        return (QueryPlan.Step) view.query.planView.rows.get(row).getUserData();
    }

    @ParameterizedTest(name = "on disk = {0}")
    @ValueSource(booleans = {false, true})
    void explainAndAnalyze(boolean onDisk, FxRobot robot, @TempDir Path tmp) {
        open(robot, onDisk, tmp);
        assertThat(view.query.planView.summary.getText()).contains("EXPLAIN ANALYZE");      // before any plan: a hint
        type(robot, "SELECT c FROM Customer c WHERE c.tier = 'GOLD' AND c.age > 30 ORDER BY c.age DESC LIMIT 5");
        robot.interact(() -> view.query.explain.fire());
        settle();
        assertThat(await(() -> view.query.planView.plan() != null)).isTrue();
        assertThat(view.query.output.getSelectionModel().getSelectedIndex()).isEqualTo(1);
        assertThat(view.query.planView.plan().analyzed()).isFalse();
        assertThat(view.query.planView.summary.getText()).startsWith("Plan only");
        assertThat(step(0).operation()).isEqualTo("OQL");
        assertThat(view.query.planView.rows).extracting(r -> ((QueryPlan.Step) r.getUserData()).operation())
                .contains("Query", "Limit", "Top-N sort", "Filter");
        assertThat(view.query.planView.rows).anyMatch(r -> r.getStyleClass().contains("k-plan-index"));
        assertThat(view.query.plan.getText()).contains("└─ ", "Top-N sort");
        assertThat(view.query.message.getText()).isEqualTo("plan");
        assertThat(view.query.planView.lookupAll(".k-plan-time")).isEmpty();          // no times without analyze

        robot.interact(() -> view.query.analyze.fire());
        settle();
        assertThat(await(() -> view.query.planView.plan() != null && view.query.planView.plan().analyzed())).isTrue();
        assertThat(view.query.planView.summary.getText()).startsWith("Ran the query: 5 rows in");
        assertThat(view.query.message.getText()).startsWith("analyzed · ");
        assertThat(view.query.planView.lookupAll(".k-plan-time")).isNotEmpty();
        assertThat(view.query.planView.lookupAll(".k-plan-flag")).hasSize(1);          // the slowest step
        assertThat(view.query.planView.rows).filteredOn(r -> r.getStyleClass().contains("k-plan-slow-row")).hasSize(1);
        assertThat(step(0).rows()).isEqualTo(5);
        assertThat(view.query.plan.getText()).contains(" ms");

        // the text view
        BorderPane pane = (BorderPane) view.query.planView.getParent();
        robot.interact(() -> view.query.planText.setSelected(true));
        assertThat(pane.getCenter()).isSameAs(view.query.plan);
        robot.interact(() -> view.query.planText.setSelected(false));
        assertThat(pane.getCenter()).isSameAs(view.query.planView);

        // EXPLAIN ANALYZE typed in the console runs through Run as well
        type(robot, "EXPLAIN ANALYZE SELECT p.title FROM Product p WHERE MATCH(p.description, 'mug') OR p.price > 10");
        robot.interact(() -> view.query.run.fire());
        settle();
        assertThat(await(() -> view.query.planView.plan() != null && view.query.planView.plan().analyzed())).isTrue();
        assertThat(view.query.message.getText()).startsWith("plan · ");
    }

    @ParameterizedTest(name = "on disk = {0}")
    @ValueSource(booleans = {false, true})
    void searchesUpdatesAndErrors(boolean onDisk, FxRobot robot, @TempDir Path tmp) {
        open(robot, onDisk, tmp);
        type(robot, "SELECT p.title FROM Product p ORDER BY cosine_distance(p.embedding, '[1, 0, 0, 0]') LIMIT 3");
        robot.interact(() -> view.query.analyze.fire());
        settle();
        assertThat(await(() -> view.query.planView.plan() != null && view.query.planView.plan().analyzed())).isTrue();
        assertThat(view.query.planView.rows).anyMatch(r -> r.getStyleClass().contains("k-plan-vector"));
        assertThat(view.query.planView.rows).extracting(r -> ((QueryPlan.Step) r.getUserData()).operation()).contains("Nearest");

        type(robot, "SELECT p.title FROM Product p ORDER BY SCORE(p.description, 'kitchen baking') DESC LIMIT 3");
        robot.interact(() -> view.query.explain.fire());
        settle();
        assertThat(await(() -> view.query.planView.rows.stream()
                .anyMatch(r -> ((QueryPlan.Step) r.getUserData()).operation().equals("Full-text search")))).isTrue();
        assertThat(view.query.planView.rows).anyMatch(r -> r.getStyleClass().contains("k-plan-fulltext"));
        HBox row = view.query.planView.rows.get(0);
        assertThat(row.getChildren()).anyMatch(n -> n instanceof Label l && l.getText().equals("OQL"));

        // UPDATE: no tree, the text plan is shown and nothing changes
        type(robot, "UPDATE Product p SET p.price = 0 WHERE p.title = 'none'");
        robot.interact(() -> view.query.explain.fire());
        settle();
        assertThat(await(() -> view.query.planText.isSelected())).isTrue();
        assertThat(view.query.planView.plan()).isNull();
        assertThat(view.query.plan.getText()).isNotBlank();

        type(robot, "EXPLAIN ANALYZE DELETE FROM Product p");
        robot.interact(() -> view.query.run.fire());
        settle();
        assertThat(view.query.message.getStyleClass()).contains("k-error");
        assertThat(view.query.message.getText()).contains("SELECT statements only");
        type(robot, "SELECT nonsense FROM");
        robot.interact(() -> view.query.analyze.fire());
        settle();
        assertThat(view.query.message.getStyleClass()).contains("k-error");
    }

    @ParameterizedTest(name = "on disk = {0}")
    @ValueSource(booleans = {false, true})
    void format(boolean onDisk, FxRobot robot, @TempDir Path tmp) {
        open(robot, onDisk, tmp);
        String raw = "select c.name,c.age from Customer c where c.tier='GOLD' and c.age>30 order by c.age desc limit 5";
        type(robot, raw);
        robot.interact(() -> view.query.format.fire());
        assertThat(view.query.editor.getText()).isEqualTo("""
                SELECT c.name, c.age
                FROM Customer c
                WHERE c.tier = 'GOLD'
                    AND c.age > 30
                ORDER BY c.age DESC
                LIMIT 5""");
        assertThat(view.query.message.getText()).isEqualTo("formatted");
        robot.interact(() -> view.query.format.fire());
        assertThat(view.query.message.getText()).isEqualTo("already formatted");
        robot.interact(() -> view.query.run.fire());                          // the formatted text runs the same
        settle();
        assertThat(view.query.results.getItems()).hasSize(5);
        robot.interact(() -> view.query.editor.undo());                       // one undo step back to the original
        assertThat(view.query.editor.getText()).isEqualTo(raw);

        type(robot, "select # from x");                                       // not OQL: left alone
        robot.interact(() -> view.query.format.fire());
        assertThat(view.query.editor.getText()).isEqualTo("select # from x");
        assertThat(view.query.message.getText()).isEqualTo("already formatted");
    }
}
