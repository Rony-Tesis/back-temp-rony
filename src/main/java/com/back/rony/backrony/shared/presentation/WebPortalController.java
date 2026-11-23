package com.back.rony.backrony.shared.presentation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@ConditionalOnProperty(name = "rony.web-portal", havingValue = "true")
public class WebPortalController {
    @GetMapping({"/dashboard", "/monitoring", "/classifications"})
    public String portal() {
        return "forward:/index.html";
    }
}
