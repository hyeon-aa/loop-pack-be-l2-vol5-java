package com.loopers.domain.like;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LikeServiceTest {

    @Mock
    private LikeRepository likeRepository;

    @Test
    void createsLikeOnlyWhenRelationshipDoesNotExist() {
        when(likeRepository.find(1L, 2L)).thenReturn(Optional.empty());

        assertThat(new LikeService(likeRepository).register(1L, 2L)).isTrue();

        verify(likeRepository).save(any(LikeModel.class));
    }

    @Test
    void keepsExistingLikeOnRepeatedRegister() {
        when(likeRepository.find(1L, 2L)).thenReturn(Optional.of(new LikeModel(1L, 2L)));

        assertThat(new LikeService(likeRepository).register(1L, 2L)).isFalse();

        verify(likeRepository, never()).save(any());
    }

    @Test
    void cancelsExistingLikeAndIgnoresAbsentLike() {
        LikeModel like = new LikeModel(1L, 2L);
        when(likeRepository.find(1L, 2L)).thenReturn(Optional.of(like));

        new LikeService(likeRepository).cancel(1L, 2L);

        verify(likeRepository).delete(like);
    }
}
