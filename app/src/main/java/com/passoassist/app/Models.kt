package com.passoassist.app

import com.google.gson.annotations.SerializedName

data class PrintJob(
    val internaldispatchserial: String,
    val content: String
)

data class AckResponse(
    val message: String,
    val internaldispatchserial: String
)

data class StatusTitle(
    @SerializedName("Description") val Description: String?,
    @SerializedName("ProductName") val ProductName: String?,
    @SerializedName("OptionalProducts") val OptionalProducts: String?,
    @SerializedName("Quantity") val Quantity: Int?,
    @SerializedName("ProductDescription") val ProductDescription: String?,
    @SerializedName("OptionsQuantity") val OptionsQuantity: String?
)

data class OrderJob(
    @SerializedName("SalesOrderSerial") val SalesOrderSerial: String?,
    @SerializedName("PersonnelName") val PersonnelName: String?,
    @SerializedName("InternalDispatchSerial") val InternalDispatchSerial: String?,
    @SerializedName("SequenceNumber") val SequenceNumber: String?,
    @SerializedName("AddedTime") val AddedTime: String?,
    @SerializedName("BranchName") val BranchName: String?,
    @SerializedName("ItemsCount") val ItemsCount: Int?,
    @SerializedName("StatusTitle") val StatusTitle: StatusTitle?
)







