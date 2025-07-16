package com.example.redirectservice;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LinkRepository extends R2dbcRepository<Link, String> {
} 