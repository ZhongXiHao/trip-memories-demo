package com.cshlands.api.currency;

import com.cshlands.api.common.ItemsResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/currencies")
class CurrencyController {
    private final CurrencyMapper currencyMapper;

    CurrencyController(CurrencyMapper currencyMapper) {
        this.currencyMapper = currencyMapper;
    }

    @GetMapping
    ResponseEntity<ItemsResponse<CurrencyView>> list() {
        return ResponseEntity.ok().body(new ItemsResponse<>(currencyMapper.list()));
    }
}
