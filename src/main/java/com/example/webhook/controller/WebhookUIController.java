package com.example.webhook.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WebhookUIController {

    @GetMapping("/")
    public String dashboard() {
        return "dashboard";
    }

    @GetMapping("/history")
    public String history() {
        return "history";
    }

    @GetMapping("/test")
    public String test() {
        return "test";
    }

    @GetMapping("/learn")
    public String learn() {
        return "learn";
    }

    @GetMapping("/demo")
    public String demo() {
        return "demo";
    }
}
