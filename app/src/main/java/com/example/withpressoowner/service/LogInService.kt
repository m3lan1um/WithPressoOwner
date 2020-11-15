package com.example.withpressoowner.service

import com.google.gson.annotations.SerializedName
import retrofit2.Call
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import java.io.Serializable

data class Login (
    @SerializedName("cafe_asin") var cafe_asin: Int,
    @SerializedName("owner_name") var owner_name: String
): Serializable

interface LogInService {
    @FormUrlEncoded
    @POST("/log_in/")
    fun requestLogin(
        @Field("user_id")id: String,
        @Field("user_pw")pw: String
    ): Call<Login>
}