-- Customer.totalOrders/totalSpent were never wired up to actually update (see
-- CustomerService/OrderRepository.getOrderStatsByBuyer) — always 0/0.00 for every customer.
-- Replaced by computing both on read from Order rows; the columns are dead weight, drop them.
alter table customers
    drop column total_orders,
    drop column total_spent;
