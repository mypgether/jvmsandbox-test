package com.gether.sanbox;

import java.util.Base64;

public class MainTest {

    public static void main(String[] args) throws IllegalAccessException {
        System.out.println(new String (Base64.getDecoder().decode("eyAiMCI6IHsgInVpZCI6ICIxMDAiLCAiZGV2aWNlaWQiOiAiZGV2aWNlMTIzIiB9LCAiMSI6IHsgInVpZCI6ICIxMDAiLCAiZGV2aWNlaWQiOiAiZGV2aWNlMTIzIiB9IH0=")));
    }
}