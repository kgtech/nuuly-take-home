# Nuuly Services Assessment

## Overview

Inventory management is a core function of any retail or e-commerce business. It involves tracking the quantity of products available for sale, adjusting stock levels as new shipments arrive, and decrementing inventory when customers make purchases. Each distinct product is typically identified by a **SKU** (Stock Keeping Unit) — a unique identifier used to track and manage individual items across the supply chain.

For this assessment, build a simple inventory management API using any programming language, framework, and data store of your choice. Your API should support receiving new stock by SKU, processing purchases, and reporting current inventory levels.

## Requirements

Implement a RESTful API conforming to the following OpenAPI specification:

```yaml
openapi: 3.0.3
info:
  title: Inventory API
  version: 1.0.0
paths:
  /inventory/{skuId}:
    parameters:
      - name: skuId
        in: path
        required: true
        schema:
          type: string
          example: CW-XYCS-BM-01
    get:
      summary: Get inventory for a SKU
      description: Returns the current quantity on hand for the specified SKU. Returns 404 if the SKU does not exist.
      operationId: getInventory
      responses:
        '200':
          description: Current inventory state for the sku
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/InventoryItem'
        '404':
          description: SKU not found
          content:
            text/plain:
              schema:
                $ref: '#/components/schemas/Error'
    post:
      summary: Create or update inventory for a SKU
      description: >
        Adds stock for the specified SKU. If the SKU does not yet exist in inventory,
        it is created with the given quantity. If it already exists, the quantity is
        added to the current stock. Returns the updated inventory state for the SKU.
      operationId: createInventory
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/InventoryQuantity'
      responses:
        '200':
          description: Current state of the item after update
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/InventoryItem'
        '400':
          description: Invalid request
          content:
            text/plain:
              schema:
                $ref: '#/components/schemas/Error'
  /inventory/{skuId}/purchase:
    parameters:
      - name: skuId
        in: path
        required: true
        schema:
          type: string
          example: widget
    post:
      summary: Purchase a quantity of a SKU
      description: >
        Deducts the requested quantity from inventory for the specified SKU.
        The purchase is only allowed if the current stock is sufficient to
        fulfill the full requested quantity. Returns 404 if the SKU does not
        exist. Returns 400 if there is insufficient inventory. On success, returns the
        remaining inventory for the SKU.
      operationId: purchaseItem
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/InventoryQuantity'
      responses:
        '200':
          description: Purchase successful; remaining inventory for the item
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/InventoryItem'
        '400':
          description: Insufficient inventory or invalid request
          content:
            text/plain:
              schema:
                $ref: '#/components/schemas/Error'
        '404':
          description: SKU not found
          content:
            text/plain:
              schema:
                $ref: '#/components/schemas/Error'
  /inventory:
    get:
      summary: List all inventory
      description: >
        Returns a list of all SKUs currently in inventory along with their quantities.
        If no SKUs exist, returns an empty array.
      operationId: listInventory
      responses:
        '200':
          description: List of all inventory items
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/InventoryItem'  
components:
  schemas:
    InventoryQuantity:
      type: object
      required:
        - quantity
      properties:
        quantity:
          type: integer
          minimum: 1
          example: 10
    InventoryItem:
      type: object
      properties:
        skuId:
          type: string
          example: widget
        quantity:
          type: integer
          minimum: 0
          example: 10
    Error:
      type: string
      example: Insufficient inventory
```



## Use of AI Tools

You are welcome to use AI tools (e.g. Claude, ChatGPT, Copilot, Cursor, etc.) as part of your development process. If you do, please:

- Commit any relevant AI tool configuration or artifact files to your repository (for example, prompt files, `.cursorrules`, agent configs, generated specs/plans, or chat exports) so we can see how they were used.
- Be prepared to discuss your AI-assisted approach during the follow-up conversation, including what you asked for, what you changed or rejected, and why.

We're not looking to penalize AI use — we're interested in how you direct and evaluate it.

## Submission Requirements

- Provide a **Git repository** of your work
- Include clear instructions on **how to build and run** your application
- The application must start and be testable with minimal setup



## How to Submit

Share a link to your Git repository (GitHub, GitLab, Bitbucket, etc.) with us.