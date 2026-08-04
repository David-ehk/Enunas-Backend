package com.enunas.backend.customer;

import com.enunas.backend.customer.dto.UserAddressDto;
import com.enunas.backend.customer.dto.UserAddressResponseDto;
import com.enunas.backend.exception.AddressNotFoundException;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserAddressService {

    private final UserAddressRepository userAddressRepository;

    @Transactional(readOnly = true)
    public List<UserAddressResponseDto> getMyAddresses(User user) {
        return userAddressRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(UserAddressResponseDto::from)
                .toList();
    }

    @Transactional
    public UserAddressResponseDto createAddress(UserAddressDto dto, User user) {
        boolean isFirst = !userAddressRepository.existsByUser(user);
        UserAddress address = UserAddress.builder()
                .user(user)
                .firstName(dto.getFirstName())
                .lastName(dto.getLastName())
                .street(dto.getStreet())
                .houseNumber(dto.getHouseNumber())
                .addressLine2(dto.getAddressLine2())
                .postalCode(dto.getPostalCode())
                .city(dto.getCity())
                .country(dto.getCountry())
                .isDefault(isFirst)
                .build();
        return UserAddressResponseDto.from(userAddressRepository.save(address));
    }

    @Transactional
    public UserAddressResponseDto updateAddress(Long id, UserAddressDto dto, User user) {
        UserAddress address = findOwned(id, user);
        address.setFirstName(dto.getFirstName());
        address.setLastName(dto.getLastName());
        address.setStreet(dto.getStreet());
        address.setHouseNumber(dto.getHouseNumber());
        address.setAddressLine2(dto.getAddressLine2());
        address.setPostalCode(dto.getPostalCode());
        address.setCity(dto.getCity());
        address.setCountry(dto.getCountry());
        return UserAddressResponseDto.from(userAddressRepository.save(address));
    }

    @Transactional
    public void deleteAddress(Long id, User user) {
        userAddressRepository.delete(findOwned(id, user));
    }

    /** No auto-promotion of another address to default on delete — see plan design notes. */
    @Transactional
    public UserAddressResponseDto setDefault(Long id, User user) {
        UserAddress toDefault = findOwned(id, user);
        userAddressRepository.findByUserOrderByCreatedAtDesc(user).forEach(a -> {
            if (!a.getId().equals(toDefault.getId()) && a.isDefault()) {
                a.setDefault(false);
                userAddressRepository.save(a);
            }
        });
        toDefault.setDefault(true);
        return UserAddressResponseDto.from(userAddressRepository.save(toDefault));
    }

    private UserAddress findOwned(Long id, User user) {
        return userAddressRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new AddressNotFoundException("Address not found: " + id));
    }
}
