package com.github.drafael.chat4j.web;

import java.util.List;
import java.util.function.BooleanSupplier;

public interface WebQueryPlanner {

    List<String> planQueries(String userPrompt, BooleanSupplier isCancelled);
}
