package com.cshlands.api.command;

@FunctionalInterface
interface CommandHandler {
    ParseResult handle(String args, ParseContext context);
}
