package com.github.kookaburra.face.view;

import com.github.kookaburra.face.StudioApplication;
import com.github.kookaburra.face.demo.DemoData;
import com.github.kookaburra.face.demo.Product;
import com.github.kookaburra.face.model.ClassMeta;
import com.github.kookaburra.face.model.Session;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Comment} in Studio, in memory and on disk: the Schema page shows the class comment as a fact and field
 * comments in their own column; data grid headers of commented fields carry an info mark whose tooltip is the
 * comment, other headers have none.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class StudioCommentTest {

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

    private TableColumn<?, ?> column(String path) {
        return view.data.table.getColumns().stream().filter(c -> path.equals(c.getUserData())).findFirst().orElseThrow();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void comments(boolean onDisk, @TempDir Path tmp, FxRobot robot) {
        Session s;
        if (onDisk) {
            s = Session.open(tmp.resolve("db"));
            DemoData.fill(s.db(), 5, 7);
            s.flush();
        } else {
            s = Session.demo();
        }
        robot.interact(() -> view.open(s));
        settle();
        robot.interact(() -> view.tree.getSelectionModel().select(StudioUiTest.findClass(view, "Product")));
        settle();

        Node sku = column("sku").getGraphic();
        assertThat(sku).isInstanceOf(Label.class);
        assertThat(((Label) sku).getTooltip().getText()).isEqualTo("Stock keeping unit, unique across all products");
        assertThat(((Label) column("price").getGraphic()).getTooltip().getText()).isEqualTo("Unit price in AUD");
        assertThat(column("title").getGraphic()).isNull();

        ClassMeta m = s.meta(Product.class.getName());
        robot.interact(() -> view.schema.metadata.show(m));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(view.schema.metadata.facts.getChildren()).filteredOn(n -> n instanceof Label)
                .extracting(n -> ((Label) n).getText()).contains("comment", "Things the shop sells: books, gadgets and home items");
        assertThat(view.schema.metadata.fields.getColumns()).extracting(TableColumn::getText).contains("comment");
        assertThat(view.schema.metadata.fields.getItems()).filteredOn(f -> f.path().equals("sku"))
                .extracting(ClassMeta.FieldMeta::comment).containsExactly("Stock keeping unit, unique across all products");
    }
}
