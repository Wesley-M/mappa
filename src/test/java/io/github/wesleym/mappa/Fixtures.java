package io.github.wesleym.mappa;

/** Shared maps for the tests: one small starter, one richer commerce schema with the interesting shapes. */
final class Fixtures {

	private Fixtures() { }

	/** Three tables, two references — the smallest map that still has a chain to lay out. */
	static MappaMap store() {
		return Mappa.schema("Store")
				.table("orders", t -> t
						.primaryKey("id", "uuid")
						.reference("customer_id", "uuid", "customers", "id")
						.reference("status_id", "int", "order_status", "id")
						.column("total", "decimal"))
				.table("customers", t -> t
						.primaryKey("id", "uuid")
						.column("email", "text"))
				.table("order_status", t -> t
						.primaryKey("id", "int")
						.column("name", "text"))
				.build();
	}

	/**
	 * A fuller commerce schema exercising every shape Mappa draws differently: a self-reference
	 * ({@code categories.parent_id}), a suggested (inferred) reference, and a VIEW entity.
	 */
	static MappaMap commerce() {
		return Mappa.schema("Commerce")
				.table("customers", t -> t
						.primaryKey("id", "uuid")
						.column("email", "text")
						.column("full_name", "text")
						.column("created_at", "timestamp"))
				.table("categories", t -> t
						.primaryKey("id", "int")
						.reference("parent_id", "int", "categories", "id")
						.column("name", "text"))
				.table("products", t -> t
						.primaryKey("id", "uuid")
						.reference("category_id", "int", "categories", "id")
						.column("sku", "text")
						.column("name", "text")
						.column("price", "decimal"))
				.table("orders", t -> t
						.primaryKey("id", "uuid")
						.reference("customer_id", "uuid", "customers", "id")
						.reference("status_id", "int", "order_status", "id")
						.column("total", "decimal")
						.column("placed_at", "timestamp"))
				.table("order_items", t -> t
						.primaryKey("id", "uuid")
						.reference("order_id", "uuid", "orders", "id")
						.reference("product_id", "uuid", "products", "id")
						.column("quantity", "int")
						.column("unit_price", "decimal"))
				.table("order_status", t -> t
						.primaryKey("id", "int")
						.column("label", "text"))
				.view("revenue_by_day", t -> t
						.column("day", "date")
						.column("gross", "decimal"))
				// A relationship Mappa would infer, not one the schema declares: drawn as a dashed edge.
				.reference("revenue_by_day", "day", "orders", "placed_at", true)
				.build();
	}
}
