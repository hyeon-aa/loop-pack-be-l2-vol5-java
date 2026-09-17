package com.loopers.domain.like;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class LikeService {

    private final LikeRepository likeRepository;

    @Transactional
    public boolean register(Long userId, Long productId) {
        if (likeRepository.find(userId, productId).isPresent()) {
            return false;
        }
        likeRepository.save(new LikeModel(userId, productId));
        return true;
    }

    @Transactional
    public void cancel(Long userId, Long productId) {
        likeRepository.find(userId, productId).ifPresent(likeRepository::delete);
    }

    @Transactional(readOnly = true)
    public List<LikeModel> getUserLikes(Long userId) {
        return likeRepository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> countByProductIds(List<Long> productIds) {
        return likeRepository.countByProductIds(productIds);
    }
}
