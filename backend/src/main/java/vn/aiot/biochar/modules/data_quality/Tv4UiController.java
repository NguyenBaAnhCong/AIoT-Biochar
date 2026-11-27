package vn.aiot.biochar.modules.data_quality;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class Tv4UiController {

    @GetMapping({"/tv4/quality", "/tv4/datasets"})
    ResponseEntity<String> page() {
        return ResponseEntity.ok().header("Content-Type", "text/html; charset=utf-8")
                .header("Cache-Control", "no-store")
                .body("""
                        <!doctype html><html lang="vi"><meta charset="utf-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <title>TV4 · Chất lượng dữ liệu & Dataset</title>
                        <body><noscript>Cần bật JavaScript để sử dụng TV4.</noscript>
                        <script type="module" src="/assets/js/app.js"></script></body></html>
                        """);
    }
}
