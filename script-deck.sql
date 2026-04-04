USE master;
GO

IF DB_ID('QLNHAXE') IS NOT NULL
BEGIN
    ALTER DATABASE QLNHAXE SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
    DROP DATABASE QLNHAXE
END
GO

-- Tạo mới CSDL
CREATE DATABASE QLNHAXE;
GO

-- Sử dụng CSDL
USE QLNHAXE;
GO

CREATE TABLE TuyenXe (
    MaTuyen VARCHAR(10) PRIMARY KEY,
    BenDi NVARCHAR(100),
    BenDen NVARCHAR(100),
    UocLuongThoiGian INT
);

CREATE TABLE Xe (
    MaXe VARCHAR(10) PRIMARY KEY,
    BienSo VARCHAR(20),
    SoCho INT,
    GhiChu NVARCHAR(200)
);

CREATE TABLE ChuyenXe (
    MaChuyen VARCHAR(10) PRIMARY KEY,
    TuyenXe VARCHAR(10),
    MaXe VARCHAR(10),
    BatDau DATETIME,
    KetThuc DATETIME,
    GhiChu NVARCHAR(200)
    FOREIGN KEY (TuyenXe) REFERENCES TuyenXe(MaTuyen),
    FOREIGN KEY (MaXe) REFERENCES Xe(MaXe)
);

CREATE TABLE PhongBan (
    MaPB VARCHAR(10) PRIMARY KEY,
    TenPB NVARCHAR(100),
    DienThoai VARCHAR(20),
    TruongPhong VARCHAR(10),
    NgayNhanChuc DATE,
);

CREATE TABLE NhanVien (
    MaNV VARCHAR(10) PRIMARY KEY,
    HoTen NVARCHAR(100),
    GioiTinh NVARCHAR(10),
    NgaySinh DATE,
    ChuyenMon NVARCHAR(100),
    DienThoai VARCHAR(20),
    DiaChi NVARCHAR(100),
    Luong DECIMAL(15, 2),
    PhongBan VARCHAR(10),
    QLCM VARCHAR(10)
    FOREIGN KEY (PhongBan) REFERENCES PhongBan(MaPB)
);

GO
-- Cập nhật lại khóa ngoại cho PhongBan sau khi bảng NhanVien đã có
ALTER TABLE PhongBan
ADD CONSTRAINT FK_TruongPhong FOREIGN KEY (TruongPhong) REFERENCES NhanVien(MaNV);

CREATE TABLE PhanCong (
    ChuyenXe VARCHAR(10),
    MaNV VARCHAR(10),
    VaiTro NVARCHAR(50),
    PRIMARY KEY (ChuyenXe, MaNV),
    FOREIGN KEY (ChuyenXe) REFERENCES ChuyenXe(MaChuyen),
    FOREIGN KEY (MaNV) REFERENCES NhanVien(MaNV)
);

INSERT INTO TuyenXe (MaTuyen, BenDi, BenDen, UocLuongThoiGian)
VALUES
('TX01', N'Hồ Chí Minh', N'Cần Thơ', 210),
('TX02', N'Cần Thơ', N'Hồ Chí Minh', 210),
('TX03', N'Hồ Chí Minh', N'Vũng Tàu', 150),
('TX04', N'Vũng Tàu', N'Hồ Chí Minh', 150),
('TX05', N'Cần Thơ', N'Rạch Giá', 150),
('TX06', N'Rạch Giá', N'Cần Thơ', 150),
('TX07', N'Vũng Tàu', N'Phan Thiết', 240),
('TX08', N'Phan Thiết', N'Vũng Tàu', 240),
('TX09', N'Hồ Chí Minh', N'Đà Lạt', 420),
('TX10', N'Đà Lạt', N'Hồ Chí Minh', 420);

INSERT INTO Xe (MaXe, BienSo, SoCho, GhiChu)
VALUES
('X01', '51A-12345', 38, N'Giường nằm'),
('X02', '51B-67890', 40, N'Giường nằm'),
('X03', '72A-11223', 40, N'Ghế ngồi'),
('X04', '72B-33445', 40, N'Ghế ngồi'),
('X05', '65A-55667', 30, N'Ghế ngồi'),
('X06', '68B-77889', 30, N'Ghế ngồi'),
('X07', '72C-99001', 35, N'Giường nằm'),
('X08', '86A-11229', 35, N'Giường nằm'),
('X09', '51C-33449', 28, N'Giường nằm'),
('X10', '49B-55679', 28, N'Giường nằm'),
('X11', '51D-77881', 40, N'Ghế ngồi'),
('X12', '63A-99002', 30, N'Ghế ngồi'),
('X13', '72D-22334', 35, N'Giường nằm'),
('X14', '86B-44556', 40, N'Giường nằm'),
('X15', '51E-66778', 40, N'Ghế ngồi');

INSERT INTO PhongBan (MaPB, TenPB, DienThoai, TruongPhong, NgayNhanChuc)
VALUES
('PB01', N'Phòng Điều Hành Chuyến Xe', '028-1234567', NULL, '2023-01-01'),
('PB02', N'Phòng Bảo Dưỡng Kỹ Thuật', '028-2345678', NULL, '2023-01-01'),
('PB03', N'Phòng Quản Lý Lộ Trình', '028-3456789', NULL, '2023-01-01'),
('PB04', N'Phòng Quản Lý Lái Xe', '028-4567890', NULL, '2023-01-01'),
('PB05', N'Phòng Dịch Vụ Hành Khách', '028-5678901', NULL, '2023-01-01');


INSERT INTO NhanVien (MaNV, HoTen, GioiTinh, NgaySinh, ChuyenMon, DienThoai, DiaChi, Luong, PhongBan)
VALUES

('NV01', N'Nguyễn Văn Hùng', N'Nam', '1980-03-12', N'Quản lý đội lái xe', '0901000001', N'Q.1, TP.HCM', 25000000.00, 'PB04'),
('NV02', N'Trần Thị Mai',   N'Nữ',  '1985-07-23', N'Quản lý dịch vụ hành khách', '0902000002', N'Q.3, TP.HCM', 24000000.00, 'PB05'),
('NV03', N'Lê Văn Thành',    N'Nam', '1987-05-10', N'Tài xế hạng E', '0903000003', N'Q.Bình Thạnh, TP.HCM', 18000000.00, 'PB04'),
('NV04', N'Phạm Quốc Dũng',  N'Nam', '1990-12-02', N'Tài xế hạng D', '0903000004', N'Q.Thủ Đức, TP.HCM',   17000000.00, 'PB04'),
('NV05', N'Ngô Minh Khang',  N'Nam', '1988-09-18', N'Tài xế hạng E', '0903000005', N'Q.Gò Vấp, TP.HCM',     18500000.00, 'PB04'),
('NV06', N'Đoàn Hữu Phúc',   N'Nam', '1992-01-27', N'Tài xế hạng D', '0903000006', N'Q.Tân Bình, TP.HCM',   16500000.00, 'PB04'),
('NV07', N'Nguyễn Văn Lợi',  N'Nam', '1986-11-05', N'Tài xế hạng E', '0903000007', N'Q.7, TP.HCM',          18200000.00, 'PB04'),
('NV08', N'Huỳnh Tấn Tài',   N'Nam', '1991-04-14', N'Tài xế hạng D', '0903000008', N'Q.Tân Phú, TP.HCM',    16800000.00, 'PB04'),
('NV09',  N'Phạm Thúy An',     N'Nữ',  '1996-06-20', N'Phụ xe', '0905000009', N'Q.Bình Tân, TP.HCM',   12000000.00, 'PB05'),
('NV10',  N'Võ Hoàng Nam',     N'Nam', '1995-10-08', N'Phụ xe',     '0905000010', N'Q.10, TP.HCM',          11500000.00, 'PB05'),
('NV11',  N'Nguyễn Mỹ Duyên',  N'Nữ',  '1997-02-11', N'Phụ xe',      '0905000011', N'Q.8, TP.HCM',           11800000.00, 'PB05'),
('NV12',  N'Lý Thanh Phong',   N'Nam', '1998-12-30', N'Phụ xe',            '0905000012', N'Q.5, TP.HCM',           11000000.00, 'PB05'),
('NV13',  N'Đặng Gia Huy',     N'Nam', '1994-03-03', N'Phụ xe','0905000013', N'Q.11, TP.HCM',      11600000.00, 'PB05'),
('NV14',  N'Bùi Thanh Trúc',   N'Nữ',  '1996-09-09', N'Phụ xe',    '0905000014', N'Q.4, TP.HCM',           11700000.00, 'PB05'),
('NV15', N'Phan Văn Trí',   N'Nam', '1989-01-15', N'Tài xế hạng E', '0903000015', N'Q.12, TP.HCM',     18000000.00, 'PB04'),
('NV16', N'Trương Minh Đức',N'Nam', '1990-04-22', N'Tài xế hạng D', '0903000016', N'Q.Bình Thạnh',     17000000.00, 'PB04'),
('NV17', N'Võ Công Toàn',   N'Nam', '1988-08-11', N'Tài xế hạng E', '0903000017', N'Q.Tân Phú',        18500000.00, 'PB04'),
('NV18', N'Đinh Hoài Nam',  N'Nam', '1991-02-09', N'Tài xế hạng D', '0903000018', N'Q.7',              16800000.00, 'PB04'),
('NV19', N'Nguyễn Hữu Tín', N'Nam', '1987-06-05', N'Tài xế hạng E', '0903000019', N'Q.10',             18200000.00, 'PB04'),
('NV20', N'Lâm Nhật Huy',   N'Nam', '1992-10-30', N'Tài xế hạng D', '0903000020', N'Q.Gò Vấp',         16500000.00, 'PB04'),
('NV21', N'Bùi Minh Hiếu',  N'Nam', '1986-03-19', N'Tài xế hạng E', '0903000021', N'Q.Bình Tân',       18300000.00, 'PB04'),
('NV22', N'Phạm Tấn Khoa',  N'Nam', '1993-09-14', N'Tài xế hạng D', '0903000022', N'Thủ Đức',          16900000.00, 'PB04'),
('NV23', N'Lê Tấn Phúc',    N'Nam', '1989-12-25', N'Tài xế hạng E', '0903000023', N'Q.Tân Bình',       18100000.00, 'PB04'),
('NV24', N'Trần Ngọc Hà',    N'Nữ',  '1997-07-07', N'Phụ xe', '0905000024', N'Q.3',   11700000.00, 'PB05'),
('NV25', N'Nguyễn Văn Duy',  N'Nam', '1996-05-21', N'Phụ xe',         '0905000025', N'Q.6',   11500000.00, 'PB05');

-- Gán trưởng phòng sau khi có dữ liệu nhân viên
UPDATE PhongBan SET TruongPhong = 'NV01' WHERE MaPB = 'PB04';
UPDATE PhongBan SET TruongPhong = 'NV02' WHERE MaPB = 'PB05';

INSERT INTO ChuyenXe (MaChuyen, TuyenXe, MaXe, BatDau, KetThuc, GhiChu)
VALUES
('CX001', 'TX01', 'X01', '2024-02-08 06:15', '2024-02-08 09:35', N'Trễ, kẹt xe'),
('CX002', 'TX02', 'X02', '2024-02-08 13:00', '2024-02-08 16:20', N'Sớm, đường thông'),
('CX003', 'TX03', 'X03', '2024-02-09 07:10', '2024-02-09 09:25', N'Sớm, ít khách'),
('CX004', 'TX04', 'X04', '2024-02-09 12:00', '2024-02-09 14:45', N'Trễ, nghỉ lâu'),
('CX005', 'TX05', 'X05', '2024-02-10 08:00', '2024-02-10 10:40', N'Trễ, mưa lớn'),
('CX006', 'TX06', 'X06', '2024-02-10 15:10', '2024-02-10 17:25', N'Sớm, ít khách'),
('CX007', 'TX07', 'X07', '2024-02-11 06:25', '2024-02-11 10:35', N'Trễ, kẹt đèo'),
('CX008', 'TX08', 'X08', '2024-02-11 11:20', '2024-02-11 15:20', N'Đúng giờ'),
('CX009', 'TX09', 'X09', '2024-02-12 05:05', '2024-02-12 12:05', N'Trễ, hỏng xe'),
('CX010', 'TX10', 'X10', '2024-02-12 14:10', '2024-02-12 21:05', N'Sớm, thời tiết tốt'),
('CX011', 'TX01', 'X11', '2024-02-13 06:00', '2024-02-13 09:50', N'Trễ, ùn tắc'),
('CX012', 'TX02', 'X12', '2024-02-13 13:05', '2024-02-13 16:15', N'Sớm, đường vắng'),
('CX013', 'TX03', 'X13', '2024-02-14 07:20', '2024-02-14 09:40', N'Đúng giờ'),
('CX014', 'TX04', 'X14', '2024-02-14 12:15', '2024-02-14 14:50', N'Sớm, không dừng'),
('CX015', 'TX05', 'X15', '2024-02-14 08:10', '2024-02-14 10:35', N'Trễ, nhiều điểm dừng');

-- Phân công tài xế và phụ xe (xe > 40 chỗ phải có phụ xe)

INSERT INTO PhanCong (ChuyenXe, MaNV, VaiTro) VALUES
('CX001','NV03',N'Tài xế'),
('CX002','NV05',N'Tài xế'),
('CX009','NV07',N'Tài xế'),
('CX010','NV15',N'Tài xế'),
('CX014','NV17',N'Tài xế'),
('CX003','NV19',N'Tài xế'),
('CX004','NV21',N'Tài xế'),
('CX011','NV23',N'Tài xế'),
('CX015','NV03',N'Tài xế'),
('CX007','NV05',N'Tài xế'),
('CX008','NV07',N'Tài xế'),
('CX013','NV15',N'Tài xế'),
('CX005','NV04',N'Tài xế'),
('CX006','NV06',N'Tài xế'),
('CX012','NV08',N'Tài xế'),
('CX001','NV09',N'Phụ xe'),
('CX002','NV10',N'Phụ xe'),
('CX009','NV11',N'Phụ xe'),
('CX010','NV12',N'Phụ xe'),
('CX014','NV13',N'Phụ xe'),
('CX007','NV24',N'Phụ xe'),
('CX008','NV25',N'Phụ xe'),
('CX013','NV14',N'Phụ xe')


--- Test data 122 - Q2
-- INSERT INTO PhanCong (ChuyenXe, MaNV, VaiTro) VALUES ('CX009','NV15',N'Tài xế');

-- INSERT INTO Xe (MaXe, BienSo, SoCho, GhiChu)
-- VALUES
-- ('X0100', '60A-23456', 38, N'Giường nằm')

--- Test data 222 - Q2
INSERT INTO PhanCong (ChuyenXe, MaNV, VaiTro) VALUES
('CX005','NV14',N'Phụ xe'),
('CX015','NV14',N'Phụ xe')

-- Đặt giá trị QLCM
UPDATE NhanVien SET QLCM = NULL WHERE MaNV IN ('NV01','NV02');
UPDATE NhanVien SET QLCM = 'NV01' WHERE MaNV IN ('NV05','NV07','NV21');
UPDATE NhanVien SET QLCM = 'NV05' WHERE MaNV IN ('NV03','NV04');
UPDATE NhanVien SET QLCM = 'NV07' WHERE MaNV IN ('NV06','NV08');
UPDATE NhanVien SET QLCM = 'NV21' WHERE MaNV IN ('NV15','NV17','NV19','NV23');
UPDATE NhanVien SET QLCM = 'NV02' WHERE MaNV IN ('NV11','NV14');
UPDATE NhanVien SET QLCM = 'NV11' WHERE MaNV IN ('NV09','NV10');
UPDATE NhanVien SET QLCM = 'NV14' WHERE MaNV IN ('NV12','NV13','NV24','NV25');

