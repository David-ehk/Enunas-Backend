package com.enunas.backend.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository extends JpaRepository<Customer, String> {
}
