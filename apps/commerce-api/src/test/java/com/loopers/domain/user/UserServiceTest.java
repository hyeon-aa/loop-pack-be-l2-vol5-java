package com.loopers.domain.user;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Test
    void returnsActiveUser() {
        UserModel user = new UserModel();
        when(userRepository.find(1L)).thenReturn(Optional.of(user));

        assertThat(new UserService(userRepository).get(1L)).isSameAs(user);
    }

    @Test
    void rejectsMissingUser() {
        when(userRepository.find(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new UserService(userRepository).get(1L))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND);
    }
}
