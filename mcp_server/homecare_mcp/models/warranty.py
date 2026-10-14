"""Warranty input/output mirrors of Spring WarrantyModels. Field names follow the Java JSON contract."""
from __future__ import annotations

from datetime import date
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

Category = Literal["REFRIGERATOR", "WASHER", "DRYER", "DISHWASHER"]


class WarrantyInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    category: Category
    modelName: str | None = Field(default=None, max_length=100)
    purchaseDate: date | None = None
    deliveryDate: date | None = None
    productionDate: date | None = None
    coreVariant: str | None = Field(default=None, max_length=50)
    productType: Literal["GENERAL", "UNKNOWN"] | None = None
    proofAvailable: bool = False
    domesticNewHousehold: bool = False


class WarrantyRow(BaseModel):
    model_config = ConfigDict(extra="forbid")
    policyId: str
    scope: Literal["PRODUCT", "CORE_PART"]
    name: str
    variant: str | None
    periodMonths: int | None
    expiresOn: date | None
    periodStatus: Literal["IN_WARRANTY", "EXPIRED", "UNKNOWN"]
    applicability: Literal["USER_DECLARED_CONDITIONAL", "NEEDS_CONFIRMATION"]
    partFee: Literal["CONDITIONAL_FREE", "PAID", "CONFIRM_REQUIRED"]
    laborFee: Literal["CONDITIONAL_FREE", "PAID", "CONFIRM_REQUIRED"]
    visitFee: Literal["CONDITIONAL_FREE", "PAID", "CONFIRM_REQUIRED"]
    conditions: list[str]


class WarrantyResult(BaseModel):
    model_config = ConfigDict(extra="forbid")
    category: Category
    modelName: str | None
    asOfDate: date
    basisDate: date | None
    basisSource: Literal["PURCHASE", "DELIVERY", "NEEDS_DATE"]
    productWarranty: WarrantyRow
    partWarranties: list[WarrantyRow]
    headline: str
    notes: list[str]
    policyVersion: str
    sourceUrl: str
    policyVerifiedOn: date
