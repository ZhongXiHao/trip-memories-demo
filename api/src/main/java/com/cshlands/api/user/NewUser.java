package com.cshlands.api.user;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class NewUser {
    private Long id;
    private String nickname;

    public NewUser(String nickname) {
        this.nickname = nickname;
    }
}
